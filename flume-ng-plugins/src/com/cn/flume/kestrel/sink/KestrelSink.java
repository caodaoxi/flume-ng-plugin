package com.cn.flume.kestrel.sink;


import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.Sink.Status;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.apache.thrift7.TException;

import com.cn.flume.kestrel.client.KestrelThriftClient;
import com.cn.flume.kestrel.util.KestrelUtil;


public class KestrelSink  extends AbstractSink implements Configurable {
	
	private KestrelThriftClient client = null;
	private String queue = null;
	private int timeout = 24 * 60 * 60 * 1000;
	private String appendField = null;
	private String appendValue= null;
	private StringBuffer buffer = null;
	
	@Override
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
			buffer = new StringBuffer();
			buffer.append(new String(e.getBody()));
			if(this.appendField == null) {
				appendValue = e.getHeaders().get(appendField);
				buffer.append("\t").append(this.appendField);
			}
			client.put(this.queue, buffer.toString(), 24 * 60 * 60 * 1000);
			tx.commit();
			return Status.READY;
		} catch(Exception e) {
			tx.rollback();
			return Status.BACKOFF;
		} finally {
			tx.close();
		}
	}

	@Override
	public void configure(Context context) {
		try {
			this.queue = KestrelUtil.getQueueName(context);
			this.timeout = Integer.parseInt(KestrelUtil.getTimeout(context));
			this.appendField = KestrelUtil.getAppendField(context);
			this.client = KestrelUtil.getClient(context);
		} catch (NumberFormatException e) {
			e.printStackTrace();
		} catch (TException e) {
			e.printStackTrace();
		}
	}

}
