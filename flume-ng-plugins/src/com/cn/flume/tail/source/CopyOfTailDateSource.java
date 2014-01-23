package com.cn.flume.tail.source;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import org.apache.flume.Event;
import org.apache.flume.Context;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.PollableSource;
import org.apache.flume.channel.ChannelProcessor;
import org.apache.flume.conf.Configurable;
import org.apache.flume.formatter.output.BucketPath;
import org.apache.flume.source.AbstractSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cn.flume.tail.util.Clock;
import com.cn.flume.tail.util.Cursor;

public class CopyOfTailDateSource  extends AbstractSource implements Configurable, PollableSource {

	private static final Logger LOG = LoggerFactory.getLogger(CopyOfTailDateSource.class);
	public static final String A_TAILSRCFILE = "tailSrcFile";
	public static SimpleDateFormat syear = new SimpleDateFormat("yyyy");
	public static SimpleDateFormat smonth = new SimpleDateFormat("MM");
	public static SimpleDateFormat sday = new SimpleDateFormat("dd");
	public static SimpleDateFormat shour = new SimpleDateFormat("HH");
	public static SimpleDateFormat smin = new SimpleDateFormat("mm");
	private static int thdCount = 0;
	private volatile boolean done = false;
	private Timer timer = new Timer();
	private final long sleepTime = 1000; // millis
	final List<Cursor> cursors = new ArrayList<Cursor>();
	private final List<Cursor> newCursors = new ArrayList<Cursor>();
	private final List<Cursor> rmCursors = new ArrayList<Cursor>();
	private ChannelProcessor channelProcessor;
	// We "queue" only allowing a single Event.
	final SynchronousQueue<Event> sync = new SynchronousQueue<Event>();

	private TailThread thd = null;


	public void configure(Context context) {
		final String fileName = context.getString("filename");
		int hour = Integer.parseInt(context.getString("hour"));
		int min = Integer.parseInt(context.getString("min"));
		int sec = Integer.parseInt(context.getString("sec"));
		final long period = Long.parseLong(context.getString("period"));
		Date cdt = new Date();
		Map<String, String> dtMap = new HashMap<String, String>();
		dtMap.put("Y", syear.format(cdt));
		dtMap.put("M", smonth.format(cdt));
		dtMap.put("D", sday.format(cdt));
		dtMap.put("h", shour.format(cdt));
		dtMap.put("m", smin.format(cdt));
		String fname = BucketPath.escapeString(fileName, dtMap);
		boolean isend = context.getBoolean("end");
		final File f = new File(fname);
		long fileLen = f.length();
		long readOffset = isend ? fileLen : 0;
		long modTime = f.lastModified();
		Cursor c = new Cursor(sync, f, readOffset, fileLen, modTime);
		addCursor(c);
		LOG.info("add new file " + fname);
		
		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				Date cdate = new Date();
				Date as = new Date(cdate.getTime() - period);
				Map<String, String> ydtMap = new HashMap<String, String>();
				
				ydtMap.put("Y", syear.format(as));
				ydtMap.put("M", smonth.format(as));
				ydtMap.put("D", sday.format(as));
				ydtMap.put("h", shour.format(as));
				ydtMap.put("m", smin.format(as));
				String asFileName = BucketPath.escapeString(fileName, ydtMap);
				ydtMap = null;
				Cursor ccursor = getCursorByFileName(asFileName);
				if(ccursor != null) {
					removeCursor(ccursor);
					LOG.info("remove " + asFileName);
				}
				
				Map<String, String> todayMap = new HashMap<String, String>();
				
				todayMap.put("Y", syear.format(cdate));
				todayMap.put("M", smonth.format(cdate));
				todayMap.put("D", sday.format(cdate));
				todayMap.put("h", shour.format(cdate));
				todayMap.put("m", smin.format(cdate));
				String newFileName = BucketPath.escapeString(fileName, todayMap);;
				todayMap = null;
				
				ccursor = getCursorByFileName(newFileName);
				
				if(ccursor == null) {
					File file = new File(newFileName);
					Cursor newCursor = new Cursor(sync, file, 0, file.length(), file.lastModified());
					addCursor(newCursor);
					LOG.info("add new file " + asFileName);
				}
			}
		};
		timer.schedule(task, getScheduleTime(hour, min, sec), period);
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
	
	synchronized public Cursor getCursorByFileName(String fileName) {
		Cursor cursor = null;
		for(Cursor c : cursors) {
			if(c.file.getAbsolutePath().equals(fileName)) {
				cursor = c;
				break;
			}
		}
		return cursor;
	}
	
	public static Date getScheduleTime(int hour, int min, int sec) {
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, hour);
		calendar.set(Calendar.MINUTE, min);
		calendar.set(Calendar.SECOND, sec);
		Date date = calendar.getTime();
		// 如果第一次执行定时任务的时间 小于 当前的时间
		// 此时要在 第一次执行定时任务的时间 加一天，以便此任务在下个时间点执行。如果不加一天，任务会立即执行。
		if (date.before(new Date())) {
			date = addDay(date, 1);
		}
		LOG.info(date.toString());
		return date;
	}

	// 增加或减少天数
	public static Date addDay(Date date, int num) {
		Calendar startDT = Calendar.getInstance();
		startDT.setTime(date);
		startDT.add(Calendar.DAY_OF_MONTH, num);
		return startDT.getTime();
	}

}
