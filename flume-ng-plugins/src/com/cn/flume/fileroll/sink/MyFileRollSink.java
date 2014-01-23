package com.cn.flume.fileroll.sink;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.formatter.output.BucketPath;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.flume.serialization.EventSerializer;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

public class MyFileRollSink extends AbstractSink implements Configurable {
	private static final Logger logger = LoggerFactory
			.getLogger(MyFileRollSink.class);
	
	private String path;
	private static final String defaultFileName = "FlumeData";
	private static final int defaultMaxOpenFiles = 50;
	/**
	 * Default length of time we wait for blocking BucketWriter calls before
	 * timing out the operation. Intended to prevent server hangs.
	 */

	private long txnEventMax;

	private MyFileWriterLinkedHashMap sfWriters;

	private String serializerType;
	private Context serializerContext;

	private SinkCounter sinkCounter;

	private int maxOpenFiles;
	
	private int hour;
	private int min;
	private long period;

	@Override
	public void configure(Context context) {
		String directory = Preconditions.checkNotNull(
				context.getString("file.path"), "file.path is required");
		String fileName = context.getString("file.filePrefix", defaultFileName);
		this.path = directory + "/" + fileName;

		maxOpenFiles = context.getInteger("file.maxOpenFiles",
				defaultMaxOpenFiles);

		serializerType = context.getString("sink.serializer", "TEXT");
		serializerContext = new Context(
				context.getSubProperties(EventSerializer.CTX_PREFIX));
		txnEventMax = context.getLong("file.txnEventMax", 1l);
		hour = context.getInteger("file.hour", 0);
		min = context.getInteger("file.min", 0);
		period = context.getInteger("file.period", 24*60*60*1000);
		if (sinkCounter == null) {
			sinkCounter = new SinkCounter(getName());
		}
	}

	@Override
	public Status process() throws EventDeliveryException {
		Channel channel = getChannel();
		Transaction transaction = channel.getTransaction();
		List<MyBucketFileWriter> writers = Lists.newArrayList();
		transaction.begin();
		try {
			Event event = null;
			int txnEventCount = 0;
			synchronized (Lock.lock) {
				for (txnEventCount = 0; txnEventCount < txnEventMax; txnEventCount++) {
					event = channel.take();
					if (event == null) {
						break;
					}
					Map<String,String> headers = event.getHeaders();
					String logtype = headers.get("logtype");
					MyBucketFileWriter bucketFileWriter = sfWriters.get(logtype);
	
					if (bucketFileWriter == null) {
						String realPath = BucketPath.escapeString(path, headers);
						bucketFileWriter = new MyBucketFileWriter();
						bucketFileWriter.open(realPath, serializerType, serializerContext, hour, min, 
								period);
						sfWriters.put(logtype, bucketFileWriter);
					}
	
					if (!writers.contains(bucketFileWriter)) {
						writers.add(bucketFileWriter);
					}
	
					// Write the data to File
					bucketFileWriter.append(event);
				}
	
				if (txnEventCount == 0) {
					sinkCounter.incrementBatchEmptyCount();
				} else if (txnEventCount == txnEventMax) {
					sinkCounter.incrementBatchCompleteCount();
				} else {
					sinkCounter.incrementBatchUnderflowCount();
				}
	
				// flush all pending buckets before committing the transaction
				for (MyBucketFileWriter bucketFileWriter : writers) {
						flush(bucketFileWriter);
				}
			}
			transaction.commit();
			if (txnEventCount > 0) {
				sinkCounter.addToEventDrainSuccessCount(txnEventCount);
			}

			if (event == null) {
				return Status.BACKOFF;
			}
			return Status.READY;
		} catch (IOException eIO) {
			transaction.rollback();
			logger.warn("File IO error", eIO);
			return Status.BACKOFF;
		} catch (Throwable th) {
			transaction.rollback();
			logger.error("process failed", th);
			if (th instanceof Error) {
				throw (Error) th;
			} else {
				throw new EventDeliveryException(th);
			}
		} finally {
			transaction.close();
		}
	}

	private void flush(MyBucketFileWriter bucketFileWriter) throws IOException {
		bucketFileWriter.flush();
	}

	@Override
	public synchronized void start() {
		super.start();
		this.sfWriters = new MyFileWriterLinkedHashMap(maxOpenFiles);
		sinkCounter.start();
	}
}
