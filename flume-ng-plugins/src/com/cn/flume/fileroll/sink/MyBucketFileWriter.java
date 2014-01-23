package com.cn.flume.fileroll.sink;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.serialization.EventSerializer;
import org.apache.flume.serialization.EventSerializerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyBucketFileWriter {

	private static final Logger logger = LoggerFactory
			.getLogger(MyBucketFileWriter.class);

	private OutputStream outputStream;
	private SimpleDateFormat sd = new SimpleDateFormat("-yyyy-MM-dd");
	private EventSerializer serializer;
	private String serializerType;
	private Context serializerContext;
	private File file;
	private Timer timer;

	public MyBucketFileWriter() {
		
	}

	public void open(final String filePath, String serializerType,
			Context serializerContext, int hour, int min, long period)
			throws IOException {

		file = new File(filePath);
		file.getParentFile().mkdirs();
		outputStream = new BufferedOutputStream(
				new FileOutputStream(file, true));
		this.serializerType = serializerType;
		this.serializerContext = serializerContext;
		serializer = EventSerializerFactory.getInstance(serializerType,
				serializerContext, outputStream);
		serializer.afterCreate();
		TimerTask action = new TimerTask() {

			@Override
			public void run() {
				synchronized (Lock.lock) {
					moveFile();
				}
			}
		};
		timer = new Timer();
		timer.schedule(action, getScheduleTime(hour, min), period);
	}

	public void append(Event event) throws IOException {
		serializer.write(event);
	}

	public boolean isBatchComplete() {
		return true;
	}

	public void flush() throws IOException {
		serializer.flush();
		outputStream.flush();

	}


	public void moveFile() {
		File destPath = new File(file.getAbsolutePath() + sd.format(new Date()));
		file.renameTo(destPath);
		try {
			outputStream = new BufferedOutputStream(new FileOutputStream(file,true));
			serializer = EventSerializerFactory.getInstance(serializerType, serializerContext, outputStream);
			serializer.afterCreate();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static Date getScheduleTime(int hour, int min) {
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, hour);
		calendar.set(Calendar.MINUTE, min);
		calendar.set(Calendar.SECOND, 0);
		Date date = calendar.getTime();
		// 如果第一次执行定时任务的时间 小于 当前的时间
		// 此时要在 第一次执行定时任务的时间 加一天，以便此任务在下个时间点执行。如果不加一天，任务会立即执行。
		if (date.before(new Date())) {
			date = addDay(date, 1);
		}
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