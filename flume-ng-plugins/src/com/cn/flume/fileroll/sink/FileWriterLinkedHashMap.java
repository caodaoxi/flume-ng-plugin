package com.cn.flume.fileroll.sink;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FileWriterLinkedHashMap extends
          LinkedHashMap<String, BucketFileWriter> {

     private static final Logger logger = LoggerFactory
               .getLogger(FileWriterLinkedHashMap.class);

     private static final long serialVersionUID = -7860596835613215998L;
     private final int maxOpenFiles;

     public FileWriterLinkedHashMap(int maxOpenFiles) {
          super(16, 0.75f, true); // stock initial capacity/load, access
          this.maxOpenFiles = maxOpenFiles;
     }

     protected boolean removeBucketFileWriter(String key) {
    	 BucketFileWriter bucketFileWriter = null;
    	 try {
    		 bucketFileWriter = this.get(key);
    		 bucketFileWriter.close();
    		 return true;
    	 } catch (IOException e) {
    		 logger.warn(key, e);
    	 } catch (InterruptedException e) {
    		 logger.warn(key, e);
    		 Thread.currentThread().interrupt();
    	 }
    	 return false;
     }
     
     protected BucketFileWriter getBucketFileWriter(String key) {
    	 return get(key);
     }
     
     protected void putBucketFileWriter(String key, BucketFileWriter bucketFileWriter) {
    	 this.put(key, bucketFileWriter);
     }
     
}