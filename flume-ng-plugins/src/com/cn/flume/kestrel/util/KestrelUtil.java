package com.cn.flume.kestrel.util;

import org.apache.flume.Context;
import org.apache.thrift7.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cn.flume.kestrel.client.KestrelConstants;
import com.cn.flume.kestrel.client.KestrelThriftClient;


public class KestrelUtil {
	
	private static final Logger log = LoggerFactory.getLogger(KestrelUtil.class);
	
	public static String getQueueName(Context context) {
		return context.getString(KestrelConstants.CONFIG_QUEUE_NAME);
	}
	
	public static String getHost(Context context) {
		return context.getString(KestrelConstants.CONFIG_HOST);
	}
	
	public static String getPort(Context context) {
		return context.getString(KestrelConstants.CONFIG_PORT);
	}
	
	public static String getTimeout(Context context) {
		return context.getString(KestrelConstants.CONFIG_TIMEOUT);
	}
	
	public static String getAppendField(Context context) {
		return context.getString(KestrelConstants.CONFIG_APPEND_FIELD);
	}
	
	public static KestrelThriftClient getClient(Context context) throws NumberFormatException, TException {
		return new KestrelThriftClient(getHost(context), Integer.parseInt(getPort(context)));
	}
}
