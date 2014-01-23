package com.cn.flume.interceptor.format;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.interceptor.Interceptor;
import org.apache.oro.text.regex.MalformedPatternException;
import org.apache.oro.text.regex.MatchResult;
import org.apache.oro.text.regex.Pattern;
import org.apache.oro.text.regex.PatternCompiler;
import org.apache.oro.text.regex.PatternMatcher;
import org.apache.oro.text.regex.Perl5Compiler;
import org.apache.oro.text.regex.Perl5Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class LogFormatInterceptor implements Interceptor {

	private static final Logger logger = LoggerFactory
			.getLogger(LogFormatInterceptor.class);

	private String conf_path = null;
	private boolean dynamicProp = false;
	private String hostname = null;

	private long propLastModify = 0;
	private long propMonitorInterval;

	private String regexp = null;
	private List<String> keys = null;

	private Pattern pattern = null;
	private PatternCompiler compiler = null;
	private PatternMatcher matcher = null;
	private SimpleDateFormat sdf = null;
	private SimpleDateFormat sd = null;
	private SimpleDateFormat sh = null;
	private SimpleDateFormat sm = null;
	private SimpleDateFormat sdfAll = null;

	private long eventCount = 0l;

	public LogFormatInterceptor(String conf_path, boolean dynamicProp,
			String hostname, long propMonitorInterval) {
		this.conf_path = conf_path;
		this.dynamicProp = dynamicProp;
		this.hostname = hostname;
		this.propMonitorInterval = propMonitorInterval;
	}

	@Override
	public void close() {

	}

	@Override
	public void initialize() {
		try {
			// 读取配置文件，初始化正在表达式和输出的key列表
			File file = new File(conf_path);
			propLastModify = file.lastModified();
			Properties props = new Properties();
			FileInputStream fis;
			fis = new FileInputStream(file);
			props.load(fis);
			regexp = props.getProperty("regexp");
			String strKey = props.getProperty("keys");
			if (strKey != null) {
				String[] strkeys = strKey.split(",");
				keys = new LinkedList<String>();
				for (String key : strkeys) {
					keys.add(key);
				}
			}
			if (keys == null) {
				logger.error("====================keys is null====================");
			} else {
				logger.info("keys=" + keys);
			}
			if (regexp == null) {
				logger.error("====================regexp is null====================");
			} else {
				logger.info("regexp=" + regexp);
			}

			// 初始化正在表达式以及时间格式化类
			compiler = new Perl5Compiler();
			pattern = compiler.compile(regexp);
			matcher = new Perl5Matcher();

			sdf = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z",
					java.util.Locale.US);
			sd = new SimpleDateFormat("yyyyMMdd");
			sh = new SimpleDateFormat("HH");
			sm = new SimpleDateFormat("mm");
			sdfAll = new SimpleDateFormat("yyyyMMddHHmmss");

		} catch (MalformedPatternException e) {
			logger.error("Could not complile pattern!", e);
		} catch (FileNotFoundException e) {
			logger.error("conf file is not found!", e);
		} catch (IOException e) {
			logger.error("conf file can not be read!", e);
		}
	}

	@Override
	public Event intercept(Event event) {
		++eventCount;
		try {
			if (dynamicProp && eventCount > propMonitorInterval) {
				File file = new File(conf_path);
				if (file.lastModified() > propLastModify) {
					propLastModify = file.lastModified();
					Properties props = new Properties();
					FileInputStream fis;
					fis = new FileInputStream(file);
					props.load(fis);
					String strKey = props.getProperty("keys");
					if (strKey != null) {
						String[] strkeys = strKey.split(",");
						List<String> keystmp = new LinkedList<String>();
						for (String key : strkeys) {
							keystmp.add(key);
						}
						if (keystmp.size() > keys.size()) {
							keys = keystmp;
							logger.info("dynamicProp status updated = " + keys);
						} else {
							logger.error("dynamicProp status new keys size less than old,so status update fail = "
									+ keys);
						}
					} else {
						logger.error("dynamicProp status get keys fail ,so status update fail = "
								+ keys);
					}

				}
			}

			Map<String, String> headers = event.getHeaders();
			headers.put("host", hostname);
			String body = new String(event.getBody());
			if (pattern != null) {
				StringBuffer stringBuffer = new StringBuffer();
				Date date = null;
				Map<String, String> index = new HashMap<String, String>();
				if (matcher.contains(body, pattern)) {
					index.put("host", hostname);
					MatchResult result = matcher.getMatch();
					index.put("ip", result.group(1));
					try {
						date = sdf.parse(result.group(2));
						index.put("loc_time", sdfAll.format(date));
					} catch (ParseException e1) {

					}
					String url = result.group(3).replaceAll(",", "|");
					String[] params = url.split("&");
					for (String param : params) {
						String[] p = param.split("=");
						if (p.length == 2) {
							index.put(p[0], p[1]);
						}
					}
					index.put("browser", result.group(4).replaceAll(",", "|"));
					for (String key : keys) {
						if (index.containsKey(key)) {
							stringBuffer.append(index.get(key) + ",");
						} else {
							stringBuffer.append("~,");
						}
					}
					if (stringBuffer.length() > 0) {
						stringBuffer.deleteCharAt(stringBuffer.length() - 1);
					} else {
						stringBuffer.append("error=" + body);
					}

					if (date != null) {
						headers.put("dayStr", sd.format(date));
						headers.put("hourStr", sh.format(date));
						Integer m = Integer.parseInt(sm.format(date));
						String min = "";
						if (m >= 0 && m < 10) {
							min = "0" + (m / 5) * 5;
						} else {
							min = (m / 5) * 5 + "";
						}
						headers.put("minStr", min);
					} else {
						headers.put("dayStr", "errorLog");
					}
					Event e = EventBuilder.withBody(stringBuffer.toString()
							.getBytes(), headers);
					return e;
				}
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

	public static class Builder implements Interceptor.Builder {

		private String confPath;
		private boolean dynamicProp;
		private String hostname;
		private long propMonitorInterval;

		@Override
		public Interceptor build() {
			return new LogFormatInterceptor(confPath, dynamicProp, hostname,
					propMonitorInterval);
		}

		@Override
		public void configure(Context context) {
			confPath = context.getString(Constants.CONF_PATH);
			dynamicProp = context.getBoolean(Constants.DYNAMICPROP,
					Constants.DYNAMICPROP_DFLT);
			hostname = context.getString(Constants.HOSTNAME,
					Constants.HOSTNAME_DFLT);
			propMonitorInterval = context.getLong(
					Constants.PROPMONITORINTERVAL,
					Constants.PROPMONITORINTERVAL_DFLT);
		}
		
		public static class Constants {
			public static String CONF_PATH = "confpath";

		    public static String DYNAMICPROP = "dynamicprop";
		    public static boolean DYNAMICPROP_DFLT = false;

		    public static String HOSTNAME = "hostname";
		    public static String HOSTNAME_DFLT = "hostname";

		    public static String PROPMONITORINTERVAL = "prop.monitor.rollInterval" ;
		    public static long PROPMONITORINTERVAL_DFLT = 500000l;
		}
	}

}
