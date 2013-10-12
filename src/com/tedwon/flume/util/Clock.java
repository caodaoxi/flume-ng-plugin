package com.tedwon.flume.util;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * This is a singleton class that wraps time functions so that they can be
 * mock'ed out later for testing.
 * 
 * Users are expected to only use the static methods to get time related values.
 */
abstract public class Clock {
  private static Clock clock = new DefaultClock();

  private final static DateFormat dateFormat = new SimpleDateFormat(
      "yyyyMMdd-HHmmssSSSZ");

  public static String timeStamp() {
    // see: http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6231579
    synchronized(dateFormat) {
      return dateFormat.format(date());
    }
  }

  static class DefaultClock extends Clock {

    @Override
    public long getNanos() {
      return System.nanoTime();
    }

    @Override
    public long getUnixTime() {
      return System.currentTimeMillis();
    }

    @Override
    public Date getDate() {
      return new Date();
    }

    @Override
    public void doSleep(long millis) throws InterruptedException {
      Thread.sleep(millis);
    }

  };

  public static void resetDefault() {
    clock = new DefaultClock();
  }

  public static void setClock(Clock c) {
    clock = c;
  }

  public static long unixTime() {
    return clock.getUnixTime();
  }

  public static long nanos() {
    return clock.getNanos();
  }

  public static Date date() {
    return clock.getDate();
  }

  public static void sleep(long millis) throws InterruptedException {
    clock.doSleep(millis);
  }

  public abstract long getUnixTime();

  public abstract long getNanos();

  public abstract Date getDate();

  abstract public void doSleep(long millis) throws InterruptedException;
}
