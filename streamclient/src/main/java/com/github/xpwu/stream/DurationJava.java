package com.github.xpwu.stream;

class DurationJava {
  static public final long Microsecond = 1;
  static public final long Millisecond = 1000 * Microsecond;
  static public final long Second = 1000 * Millisecond;
  static public final long Minute = 60 * Second;
  static public final long Hour = 60 * Minute;

  // 10 second: 10*Duration.Second
  DurationJava(long d) {
    this.d = d;
  }

  long second() {
    return d/Second;
  }

  long milliSecond() {
    return d/Millisecond;
  }

  long minute() {
    return d/Minute;
  }

  private final long d;
}
