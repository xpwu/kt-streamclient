package com.github.xpwu.stream;

interface Net {

  interface Delegate {
    void onConnected();
    void onMessage(byte[] message);
    void onClosed(String reason);
    // 连接错误与通信错误都通过这里返回
    void onError(Error error);
  }

  class Config {
    DurationJava ConnectTimeout;
    DurationJava HearBeatTime;
    DurationJava FrameTimeout; // 同一帧里面的数据延时
    public int MaxConcurrent = 5; // 一个连接上的最大并发
    public long MaxBytes = 1024 * 1024; // 一次数据发送的最大字节数

    Config(DurationJava c, DurationJava h, DurationJava f) {
      this.ConnectTimeout = c;
      this.FrameTimeout = f;
      this.HearBeatTime = h;
    }
  }

  void connect(String host, int port, TLSStrategy tls);
  void close();

  void send(byte[] content) throws Exception;
  void sendForce(byte[] content);
  void receivedOneResponse();

  void setDelegate(Delegate delegate);
  void setConfig(Config config);
}
