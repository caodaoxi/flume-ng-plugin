package com.tedwon.flume.source;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import org.apache.flume.Event;
import org.apache.flume.Context;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.PollableSource;
import org.apache.flume.channel.ChannelProcessor;
import org.apache.flume.conf.Configurable;
import org.apache.flume.source.AbstractSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tedwon.flume.handlers.text.Cursor;
import com.tedwon.flume.util.Clock;

public class TailSource  extends AbstractSource implements Configurable, PollableSource {

	private static final Logger LOG = LoggerFactory.getLogger(TailSource.class);
	public static final String A_TAILSRCFILE = "tailSrcFile";

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


	public void configure(Context context) {
		String fileName = context.getString("filename");
		boolean isend = context.getBoolean("end");
		File f = new File(fileName);
		long fileLen = f.length();
		long readOffset = isend ? fileLen : 0;
		long modTime = f.lastModified();
		Cursor c = new Cursor(sync, f, readOffset, fileLen, modTime);
		addCursor(c);
	}

	@Override
	public void start() {
		if (thd != null) {
			throw new IllegalStateException(
					"Attempted to open tail source twice!");
		}
		thd = new TailThread();
		thd.start();
		channelProcessor = getChannelProcessor();
	}

	@Override
	public void stop() {
		try {
			synchronized (this) {
				done = true;
				if (thd == null) {
					LOG.warn("TailSource double closed");
					return;
				}
				while (thd.isAlive()) {
					thd.join(100L);
					thd.interrupt();
				}
				thd = null;
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
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
