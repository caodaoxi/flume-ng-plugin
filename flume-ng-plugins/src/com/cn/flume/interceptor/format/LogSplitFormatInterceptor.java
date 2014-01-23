package com.cn.flume.interceptor.format;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.interceptor.Interceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cn.flume.interceptor.format.util.LogUtils;

public class LogSplitFormatInterceptor implements Interceptor {

	private static final Logger logger = LoggerFactory
			.getLogger(LogSplitFormatInterceptor.class);

	private String confPath = null;
	private boolean isCheck = false;

	private long propLastModify = 0;
	private long checkInterval;
	private String delimiter;
	private String dateRegexp = "dd/MMM/yyyy:HH:mm:ss Z";
	private String dateFieldName = "time_local";
	
	Map<String,List<String>> logFormatMap = null;

	private SimpleDateFormat sdf = null;
	private SimpleDateFormat sdh = null;
	private SimpleDateFormat sd = null;

	private long eventCount = 0l;
	public LogSplitFormatInterceptor(String confPath, boolean isCheck,
			String delimiter, long checkInterval) {
		this.confPath = confPath;
		this.isCheck = isCheck;
		this.checkInterval = checkInterval;
		this.delimiter = delimiter;
	}

	@Override
	public void close() {

	}

	@Override
	public void initialize() {
		try {
			File file = new File(confPath);
			logFormatMap = loadConf(file);
			sdh = new SimpleDateFormat("yyyy-MM-dd-HH");
			sd = new SimpleDateFormat("yyyy-MM-dd");
		} catch (FileNotFoundException e) {
			logger.error("conf file is not found!", e);
		} catch (IOException e) {
			logger.error("conf file can not be read!", e);
		}
	}

	@Override
	public Event intercept(Event event) {
		++eventCount;
		Map<String, String> headers = event.getHeaders();
		String logType = null;
		List<String> fields = null;
		if(!headers.containsKey("logtype")) {
			logger.error("logType is not assigned");
			return null;
		} else {
			logType = headers.get("logtype");
			fields = logFormatMap.get(logType);
		}
		try {
			if (isCheck && eventCount > checkInterval) {
				eventCount = 0L;
				File file = new File(confPath);
				if (file.lastModified() > propLastModify) {
					Map<String, List<String>> newLogFormatMap = loadConf(file);
					List<String> newfields = newLogFormatMap.get(logType);
					if (newfields != null) {
						if(fields != null) {
							if (newfields.size() > fields.size()) {
								logFormatMap = newLogFormatMap;
								logger.warn("log format is updated");
							} if(newfields.size() == fields.size()) {
								logger.info("log format is not Changed");
							} else {
								logger.error("log format new  size less than old,so update fail");
							}
						} else {
							logger.warn("logType + " + logType + " old format is not found,so old format is updated");
							fields = newfields;
						}
						
					} else {
						logger.error("get keys fail ,so update fail");
					}

				}
			}
			
			Date date = new Date();
			headers.put("dayStr", sd.format(date));
			headers.put("dayhourStr", sdh.format(date));
			Event e = null;
			if(fields == null) {
				e = EventBuilder.withBody(event.getBody(), headers);
				return e;
			}
			String body = new String(event.getBody());
			String[] values = body.split(delimiter, -1);
			Map<String, String> logKeyValueMap = LogUtils.arrayToMap(fields, values);
			List<String> headerFields = logFormatMap.get(logType + "@header");
			if(headerFields != null) {
				for(String headerField : headerFields) {
					headers.put(headerField, logKeyValueMap.get(headerField));
				}
			}
			
			List<String> deleteFields = logFormatMap.get(logType + "@delete");
			StringBuffer buffer = new StringBuffer();
			if(deleteFields != null) {
				buffer = new StringBuffer();
				String field = null;
				for(int i = 0; i < fields.size(); i++) {
					field = fields.get(i);
					if(!deleteFields.contains(field)) {
						buffer.append(logKeyValueMap.get(field));
						if(i < fields.size() - 1) {
							buffer.append("\t");
						}
					}
					field = null;
				}
			} else {
				buffer.append(body);
			}
			
			List<String> addFields = logFormatMap.get(logType + "@add");
			
			if(addFields == null) {
				e = EventBuilder.withBody(buffer.toString().getBytes(), headers);
				return e;
			} else {
				for(String field : addFields) {
					buffer.append("\t").append(headers.get(field) == null ? "-" : headers.get(field));
				}
				e = EventBuilder.withBody(buffer.toString().getBytes(), headers);
				return e;
			}
				
			
		} catch (Exception e) {
			logger.error("LogFormat error!", e);
		}
		return null;
	}

	@Override
	public List<Event> intercept(List<Event> events) {
		List<Event> list = new LinkedList<Event>();
		for (Event event : events) {
			Event e = intercept(event);
			if (e != null) {
				list.add(e);
			}
		}
		return list;
	}
	
	private Map<String, List<String>> loadConf(File file) throws FileNotFoundException, IOException {
		
		propLastModify = file.lastModified();
		Properties pps = new Properties();
		FileInputStream in;
		in = new FileInputStream(file);
		Map<String, List<String>> logMap = new HashMap<String, List<String>>();
		pps.load(in);
		Set keys = pps.keySet();
		String k = null;
		for(Object key : keys) {
			k = key.toString();
			logMap.put(key.toString(), Arrays.asList(pps.getProperty(k).split(",")));
		}
		return logMap;
	}

	public static class Builder implements Interceptor.Builder {

		private String confPath;
		private boolean isCheck;
		private long checkInterval;
		private String delimiter;
		private String dateRegexp;
		private String dateFieldName;

		@Override
		public Interceptor build() {
			return new LogSplitFormatInterceptor(confPath, isCheck, delimiter,checkInterval);
		}

		@Override
		public void configure(Context context) {
			confPath = context.getString(Constants.CONFIG_PATH);
			isCheck = context.getBoolean(Constants.ISCHECK,
					Constants.ISCHECK_DEFAULT);
			checkInterval = context.getLong(Constants.CHECKINTERVAL,
					Constants.CHECKINTERVAL_DEFAULT);
			delimiter = context.getString(Constants.DELIMITER,
					Constants.DELIMITER_DEFAULT);
		}
		
		public static class Constants {
			//配置文件路径
			public static String CONFIG_PATH = "conf.path";
			//是否对配置文件定期作变更检查
			public static String ISCHECK = "ischeck";
			public static boolean ISCHECK_DEFAULT = false;
			//是否对配置文件定期作变更检查周期
			public static String CHECKINTERVAL = "checkinterval";
			public static long CHECKINTERVAL_DEFAULT = 500000l;
			//对日志分割的分割符
			public static String DELIMITER = "delimiter";
			public static String DELIMITER_DEFAULT = "\t";
		}
	}

}
