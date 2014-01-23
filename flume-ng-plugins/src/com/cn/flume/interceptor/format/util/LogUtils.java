package com.cn.flume.interceptor.format.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LogUtils {
	public static Map<String, String> arrayToMap(List<String> keys, String[] values) throws Exception{
		Map<String, String> kv = new HashMap<String, String>();
		List<String> vals = Arrays.asList(values);
		int vsize = values.length;
		for(int i = 0; i < keys.size(); i++) {
			if(i < vsize) {
				kv.put(keys.get(i), vals.get(i));
			} else {
				kv.put(keys.get(i), "-");
			}
		}
		return kv;
	}
	
	public static String rltrim(String line, String ...fixs) {
		for (String fix : fixs) {
			if(line.startsWith(fix)) {
				line = line.substring(fix.length());
			}
			if(line.endsWith(fix)) {
				line = line.substring(0, line.length()-fix.length());
			}
		}
		return line;
	}
}
