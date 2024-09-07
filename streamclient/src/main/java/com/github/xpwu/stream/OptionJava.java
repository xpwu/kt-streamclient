package com.github.xpwu.stream;

import java.net.Socket;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

class OptionJava {
  static class Value {
    String host;
    int port;
    TLSStrategy tlsStrategy;
    DurationJava connectTimeout;
    DurationJava heartbeatTime;
    DurationJava frameTimeout; // 同一帧里面的数据延时
    DurationJava requestTimeout; //请求到响应的超时

    public Value() {
      this.host = "127.0.0.1";
      this.port = 10000;
      this.connectTimeout = new DurationJava(30* DurationJava.Second);
      this.heartbeatTime = new DurationJava(4* DurationJava.Minute);
      this.frameTimeout = new DurationJava(5* DurationJava.Second);
      this.requestTimeout = new DurationJava(15* DurationJava.Second);
      this.tlsStrategy = new TLSStrategy() {
        @Override
        public Socket TLS(String host, int port, Socket tcpSocket) throws SSLHandshakeException {
          return tcpSocket;
        }
      };
    }
  }

  static OptionJava Host(String host) {
    return new OptionJava(new Setter() {
      @Override
      public void configValue(Value value) {
        value.host = host;
      }
    });
  }

  static OptionJava Port(int port) {
    return new OptionJava(new Setter() {
      @Override
      public void configValue(Value value) {
        value.port = port;
      }
    });
  }

  static OptionJava TLS() {
    return TLS(new TLSStrategy() {
      @Override
      public Socket TLS(String host, int port, Socket tcpSocket) throws SSLHandshakeException {
        SSLSocket sslSocket = null;

        try {
          SSLContext context = SSLContext.getInstance("TLS");
          context.init(null, null, null);
          SSLSocketFactory factory = context.getSocketFactory();
          sslSocket = (SSLSocket) factory.createSocket(tcpSocket, host, port, true);
          sslSocket.startHandshake();
        } catch (Exception e) {
          e.printStackTrace();
          throw new SSLHandshakeException(e.toString());
        }

        SSLSession sslSession = sslSocket.getSession();
        // 使用默认的HostnameVerifier来验证主机名
        HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
        if (!hv.verify(host, sslSession)) {
          try {
            throw new SSLHandshakeException("Expected " + host + ", got " + sslSession.getPeerPrincipal());
          } catch (SSLPeerUnverifiedException e) {
            e.printStackTrace();
            throw new SSLHandshakeException(e.toString());
          }
        }

        return sslSocket;
      }
    });
  }

  static OptionJava TLS(TLSStrategy strategy) {
    return new OptionJava(new Setter() {
      @Override
      public void configValue(Value value) {
        value.tlsStrategy = strategy;
      }
    });
  }

  static OptionJava ConnectTimeout(DurationJava durationJava) {
    return new OptionJava(new Setter() {
      @Override
      public void configValue(Value value) {
        value.connectTimeout = durationJava;
      }
    });
  }

  static OptionJava RequestTimeout(DurationJava durationJava) {
    return new OptionJava(new Setter() {
      @Override
      public void configValue(Value value) {
        value.requestTimeout = durationJava;
      }
    });
  }

  // 由握手协议，在服务器中读取
  @Deprecated
  static OptionJava HeartbeatTime(DurationJava durationJava) {
    return new OptionJava(new Setter() {
      @Override
      public void configValue(Value value) {
//        value.heartbeatTime = duration;
      }
    });
  }

  // 由握手协议，在服务器中读取
  @Deprecated
  static OptionJava FrameTimeout(DurationJava durationJava) {
    return new OptionJava(new Setter() {
      @Override
      public void configValue(Value value) {
        value.frameTimeout = durationJava;
      }
    });
  }

  public void configValue(Value value) {
    this.setter.configValue(value);
  }

  private interface Setter {
    void configValue(Value value);
  }

  private OptionJava(Setter setter) {
    this.setter = setter;
  }

  private final Setter setter;
}
