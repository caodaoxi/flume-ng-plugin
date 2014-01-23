package com.cn.flume.fileroll.sink;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class MyFileWriterLinkedHashMap extends
          LinkedHashMap<String, MyBucketFileWriter> {

     private static final Logger logger = LoggerFactory
               .getLogger(MyFileWriterLinkedHashMap.class);

     private static final long serialVersionUID = -7860596835613215998L;
     private final int maxOpenFiles;

     public MyFileWriterLinkedHashMap(int maxOpenFiles) {
          super(16, 0.75f, true); // stock initial capacity/load, access
          this.maxOpenFiles = maxOpenFiles;
     }

     protected MyBucketFileWriter getBucketFileWriter(String key) {
    	 return get(key);
     }
     
     protected void putBucketFileWriter(String key, MyBucketFileWriter bucketFileWriter) {
    	 this.put(key, bucketFileWriter);
     }
}