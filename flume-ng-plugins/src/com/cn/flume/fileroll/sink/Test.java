package com.cn.flume.fileroll.sink;

import java.io.File;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.apache.flume.formatter.output.BucketPath;

public class Test {

	public static void main(String[] args) {
		Map<String, String> headers = new HashMap<String, String>();
		headers.put("dayhourStr", "2013-11-23-11");
		String path = "/usr/local/log-%{dayhourStr}";
		String realPath = BucketPath
				.escapeString(path, headers);
		System.out.println(new File(realPath).getAbsolutePath());
		System.out.println(realPath);

	}

}
