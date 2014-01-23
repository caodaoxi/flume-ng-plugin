package com.cn.flume.fileroll.sink;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.serialization.EventSerializer;
import org.apache.flume.serialization.EventSerializerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BucketFileWriter {

	private static final Logger logger = LoggerFactory
			.getLogger(BucketFileWriter.class);
	
	private final AtomicLong fileWriteCounter;
	private OutputStream outputStream;

	private EventSerializer serializer;

	private String filePath;

	public BucketFileWriter() {
		fileWriteCounter = new AtomicLong(0);
	}

	public void open(final String filePath, String serializerType,
               Context serializerContext, final long rollInterval,
               final ScheduledExecutorService timedRollerPool,
               final FileWriterLinkedHashMap sfWriters) throws IOException {
		
        this.filePath = filePath;
        File file = new File(this.filePath);
        file.getParentFile().mkdirs();
        outputStream = new BufferedOutputStream(new FileOutputStream(file));
        logger.info("filename = " + file.getAbsolutePath());
        serializer = EventSerializerFactory.getInstance(serializerType,serializerContext, outputStream);
        serializer.afterCreate();
        if (rollInterval > 0) {
        	Callable<Void> action = new Callable<Void>() {
        		@Override
        		public Void call() throws Exception {
        			if (fileWriteCounter.incrementAndGet() > 3 && sfWriters.containsKey(filePath)) {
        				sfWriters.removeBucketFileWriter(filePath);
        			}
        			return null;
        		}
            };
            timedRollerPool.schedule(action, rollInterval, TimeUnit.SECONDS);
         }
     }

	public void append(Event event) throws IOException {
		fileWriteCounter.getAndSet(0);
		serializer.write(event);
	}

	public boolean isBatchComplete() {
		return true;
	}

	public void flush() throws IOException {
		serializer.flush();
		outputStream.flush();

	}

	public synchronized void close() throws IOException, InterruptedException {
		synchronized (Lock.lock) {
			if (outputStream != null) {
				outputStream.flush();
				outputStream.close();
			}
		}
	}
}