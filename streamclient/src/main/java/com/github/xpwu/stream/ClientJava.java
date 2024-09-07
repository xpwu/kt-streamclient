package com.github.xpwu.stream;

import android.os.Handler;
import android.util.Log;

import java.util.Map;

class ClientJava {

  ClientJava(OptionJava... optionJavas) {
    OptionJava.Value value = new OptionJava.Value();
    for (OptionJava op : optionJavas) {
      op.configValue(value);
    }

    this.impl = new ClientImpl();

    this.impl.config = value;

    this.impl.pushCallback = new PushCallback() {
      @Override
      public void onPush(byte[] data) {
        Log.e("Client.onPush", "not set push callback");
      }
    };

    this.impl.peerClosedCallback = new PeerClosedCallback() {
      @Override
      public void onPeerClosed() {

      }
    };

    impl.setNet(new LenContent());
  }

  void updateOptions(OptionJava... optionJavas) {
    for (OptionJava op : optionJavas) {
      op.configValue(this.impl.config);
    }
    this.impl.updateNetConnectTime();
  }


  interface PushCallback {
    void onPush(byte[] data);
  }
  void setPushCallback(PushCallback delegate) {
    this.impl.pushCallback = new PushCallback() {
      @Override
      public void onPush(byte[] data) {
        // 异步回调push
        new Handler().post(new Runnable() {
          @Override
          public void run() {
            delegate.onPush(data);
          }
        });
      }
    };
  }


  interface PeerClosedCallback {
    void onPeerClosed();
  }
  void setPeerClosedCallback(PeerClosedCallback delegate) {
    this.impl.peerClosedCallback = new PeerClosedCallback() {
      @Override
      public void onPeerClosed() {
        // 异步回调 PeerClosedCallback
        new Handler().post(new Runnable() {
          @Override
          public void run() {
            delegate.onPeerClosed();
          }
        });
      }
    };
  }


  interface ErrorHandler {
    void onFailed(Error error, boolean isConnError);
  }
  interface ResponseHandler extends ErrorHandler{
    void onSuccess(byte[] response);
  }
  // 自动连接并发送数据
  void Send(byte[] data, Map<String, String> headers, ResponseHandler handler) {
    connect(new ConnectHandler() {
      @Override
      public void onSuccess() {
        onlySend(data, headers, handler);
      }

      @Override
      public void onFailed(Error error, boolean isConn) {
        handler.onFailed(error, isConn);
      }
    });
  }

  interface RecoverHandler extends ConnectHandler {}
  public void Recover(RecoverHandler handler) {
    connect(handler);
  }

  @Override
  protected void finalize() throws Throwable {
    close();
    super.finalize();
  }

  // 暂不暴露以下接口，需要进一步验证其稳定性

  private void setNet(Net net) {
    this.impl.setNet(net);
  }

  interface ConnectHandler extends ErrorHandler{
    void onSuccess();
  }
  // 无论当前连接状态，都可以重复调用，如果连接成功，确保最后的状态为连接
  // 无论多少次调用，最后都只有一条连接
  void connect(ConnectHandler handler) {
    impl.connect(handler);
  }

  // 无论当前连接状态，都可以重复调用，并确保最后的状态为关闭
  void close() {
    impl.close();
  }


  // 如果还没有连接，返回失败
  void onlySend(byte[] data, Map<String, String> headers, ResponseHandler handler) {
    impl.send(data, headers, handler);
  }


  private final ClientImpl impl;
}
