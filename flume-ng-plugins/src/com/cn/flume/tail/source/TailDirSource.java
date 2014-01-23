package com.cn.flume.tail.source;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.PollableSource;
import org.apache.flume.PollableSource.Status;
import org.apache.flume.channel.ChannelProcessor;
import org.apache.flume.conf.Configurable;
import org.apache.flume.source.AbstractSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cn.flume.tail.source.TailSource.TailThread;
import com.cn.flume.tail.util.Clock;
import com.cn.flume.tail.util.Cursor;
import com.cn.flume.tail.util.DirChangeHandler;
import com.cn.flume.tail.util.DirWatcher;
import com.cn.flume.tail.util.RegexFileFilter;
import com.google.common.base.Preconditions;

/**
 * This source tails all the file in a directory that match a specified regular
 * expression.
 */
public class TailDirSource extends AbstractSource implements Configurable,
		PollableSource {

	public static final Logger LOG = LoggerFactory
			.getLogger(TailDirSource.class);
	public static final String USAGE = "usage: tailDir(\"dirname\"[, fileregex=\".*\"[, startFromEnd=false[, recurseDepth=0]]])";
	
	private DirWatcher watcher;
	private ConcurrentMap<String, DirWatcher> subdirWatcherMap;
	private TailSource tail;
	private File dir;
	private String regex;
	private boolean startFromEnd;
	private int recurseDepth;

	private static int thdCount = 0;
	private volatile boolean done = false;

	private final long sleepTime = 1000; // millis
	final List<Cursor> cursors = new ArrayList<Cursor>();
	private final List<Cursor> newCursors = new ArrayList<Cursor>();
	private final List<Cursor> rmCursors = new ArrayList<Cursor>();
	private ChannelProcessor channelProcessor;
	// We "queue" only allowing a single Event.
	final SynchronousQueue<Event> sync = new SynchronousQueue<Event>();

	private TailThread thd = null;

	// Indicates whether dir was checked. It is false before source is open
	// and set to true after the first check of a dir
	private volatile boolean dirChecked = false;

	final private AtomicLong filesAdded = new AtomicLong();
	final private AtomicLong filesDeleted = new AtomicLong();
	final private AtomicLong subdirsAdded = new AtomicLong();
	final private AtomicLong subdirsDeleted = new AtomicLong();

	final public static String A_FILESADDED = "filesAdded";
	final public static String A_FILESDELETED = "filesDeleted";
	final public static String A_FILESPRESENT = "filesPresent";
	final public static String A_SUBDIRSADDED = "subdirsAdded";
	final public static String A_SUBDIRSDELETED = "subdirsDeleted";

	

	@Override
	public void configure(org.apache.flume.Context context) {
		
		File f = new File(context.getString("dir"));
		Preconditions.checkArgument(f != null, "File should not be null!");
		this.dir = f;
		this.startFromEnd = Boolean.parseBoolean(context.getString("end"));
		this.recurseDepth = Integer.parseInt(context.getString("depth"));;
		this.regex = context.getString("regex");
		
	}
	
	@Override
	public synchronized void start() {
		Preconditions.checkState(watcher == null,
				"Attempting to open an already open TailDirSource (" + dir
				+ ", \"" + regex + "\")");
		subdirWatcherMap = new ConcurrentHashMap<String, DirWatcher>();
		watcher = createWatcher(dir, regex, recurseDepth);
		dirChecked = true;
		watcher.start();
		
		if (thd != null) {
			throw new IllegalStateException(
					"Attempted to open tail source twice!");
		}
		thd = new TailThread();
		thd.start();
		channelProcessor = getChannelProcessor();
	}
	
	@Override
	public Status process() throws EventDeliveryException {
		Status status = Status.READY;
		channelProcessor = getChannelProcessor();
		try {
			while (!done) {
				// This blocks on the synchronized queue until a new event
				// arrives.
				Event e = sync.poll(100, TimeUnit.MILLISECONDS);
				if (e == null)
					continue; // nothing there, retry.
				channelProcessor.processEvent(e);
				status = Status.READY;
			}
		} catch (Throwable t) {

			status = Status.BACKOFF;
			if (t instanceof Error) {
				throw (Error) t;
			}
		}
		return status;
	}
	
	/**
	 * Must be synchronized to isolate watcher
	 * 
	 * @throws InterruptedException
	 */
	synchronized public void close() throws IOException, InterruptedException {
		this.watcher.stop();
		this.watcher = null;
		for (DirWatcher watcher : subdirWatcherMap.values()) {
			watcher.stop();
		}
		subdirWatcherMap = null;
	}



	private DirWatcher createWatcher(File dir, final String regex,
			final int recurseDepth) {
		// 250 ms between checks
		DirWatcher watcher = new DirWatcher(dir, new RegexFileFilter(regex),
				250);
		watcher.addHandler(new DirChangeHandler() {
			Map<String, Cursor> curmap = new HashMap<String, Cursor>();
			
			@Override
			public void fileCreated(File f) {
				if (f.isDirectory()) {
					if (recurseDepth <= 0) {
						LOG.debug("Tail dir will not read or recurse "
								+ "into subdirectory " + f
								+ ", this watcher recurseDepth: "
								+ recurseDepth);
						return;
					}
					
					LOG.info("added dir " + f + ", recurseDepth: "
							+ (recurseDepth - 1));
					DirWatcher watcher = createWatcher(f, regex,
							recurseDepth - 1);
					watcher.start();
					subdirWatcherMap.put(f.getPath(), watcher);
					subdirsAdded.incrementAndGet();
					return;
				}
				
				// Add a new file to the multi tail.
				LOG.info("added file " + f);
				Cursor c;
				if (startFromEnd && !dirChecked) {
					// init cursor positions on first dir check when
					// startFromEnd is set
					// to true
					c = new Cursor(sync, f, f.length(), f.length(), f
							.lastModified());
					try {
						c.initCursorPos();
					} catch (InterruptedException e) {
						LOG.error("Initializing of cursor failed", e);
						c.close();
						return;
					}
				} else {
					c = new Cursor(sync, f);
				}
				
				curmap.put(f.getPath(), c);
				addCursor(c);
				filesAdded.incrementAndGet();
			}
			
			@Override
			public void fileDeleted(File f) {
				LOG.debug("handling deletion of file " + f);
				String fileName = f.getPath();
				// we cannot just check here with f.isDirectory() because f was
				// deleted
				// and f.isDirectory() will return false always
				DirWatcher watcher = subdirWatcherMap.remove(fileName);
				if (watcher != null) {
					LOG.info("removed dir " + f);
					LOG.info("stopping watcher for dir: " + f);
					// stop is not thread-safe, but since this watcher belongs
					// only to
					// this current thread it is safe to call it
					watcher.stop();
					// calling check explicitly to notify about deleted subdirs,
					// so that subdirs watchers can be stopped
					watcher.check();
					subdirsDeleted.incrementAndGet();
					return;
				}
				
				Cursor c = curmap.remove(fileName);
				// this check may seem unneeded but there are cases which it
				// handles,
				// e.g. if unwatched subdirectory was removed c is null.
				if (c != null) {
					LOG.info("removed file " + f);
					removeCursor(c);
					filesDeleted.incrementAndGet();
				}
			}
			
		});
		
		// Separate check is needed to init cursor positions
		// (to the end of the files in dir)
		if (startFromEnd) {
			watcher.check();
		}
		return watcher;
	}
	
	class TailThread extends Thread {

		TailThread() {
			super("TailThread-" + thdCount++);
		}

		@Override
		public void run() {
			try {
				// initialize based on initial settings.
				for (Cursor c : cursors) {
					c.initCursorPos();
				}

				while (!done) {
					synchronized (newCursors) {
						cursors.addAll(newCursors);
						newCursors.clear();
					}

					synchronized (rmCursors) {
						cursors.removeAll(rmCursors);
						for (Cursor c : rmCursors) {
							c.flush();
						}
						rmCursors.clear();
					}

					boolean madeProgress = false;
					for (Cursor c : cursors) {
						LOG.debug("Progress loop: " + c.file);
						if (c.tailBody()) {
							madeProgress = true;
						}
					}

					if (!madeProgress) {
						Clock.sleep(sleepTime);
					}
				}
				LOG.debug("Tail got done flag");
			} catch (InterruptedException e) {
				LOG.error("Tail thread nterrupted: " + e.getMessage(), e);
			} finally {
				LOG.info("TailThread has exited");
			}

		}
	}

	synchronized void addCursor(Cursor cursor) {

		if (thd == null) {
			cursors.add(cursor);
			LOG.debug("Unstarted Tail has added cursor: "
					+ cursor.file.getName());
		} else {
			synchronized (newCursors) {
				newCursors.add(cursor);
			}
			LOG.debug("Tail added new cursor to new cursor list: "
					+ cursor.file.getName());
		}
		
	}

	/**
	 * Remove an existing cursor to tail.
	 */
	synchronized public void removeCursor(Cursor cursor) {
		if (thd == null) {
			cursors.remove(cursor);
		} else {

			synchronized (rmCursors) {
				rmCursors.add(cursor);
			}
		}

	}
}