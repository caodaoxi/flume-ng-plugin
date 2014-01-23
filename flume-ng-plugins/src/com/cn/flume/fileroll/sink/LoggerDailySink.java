package com.cn.flume.fileroll.sink;


import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.apache.log4j.Logger;


public class LoggerDailySink extends AbstractSink implements Configurable{
	private static final Logger log = Logger.getLogger("ROLLFILE");
	
	public Status process() throws EventDeliveryException {
		Channel channel = getChannel();
		Transaction tx = channel.getTransaction();
		try {
			tx.begin();
			Event e = channel.take();
			if(e==null) {
				tx.rollback();
				return Status.BACKOFF;
			}
			try {
				log.info(new String(e.getBody()));
				tx.commit();
				return Status.READY;
			} catch(Exception ex) {
				throw ex;
			}
		} catch(Exception e) {
			tx.rollback();
			return Status.BACKOFF;
		} finally {
			tx.close();
		}
	}

	public void configure(Context context) {
	}

	@Override
	public synchronized void start() {
		super.start();
	}

	@Override
	public synchronized void stop() {
		super.stop();
	}
}
