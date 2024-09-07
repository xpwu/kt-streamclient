package com.github.xpwu.stream;

import java.net.Socket;

import javax.net.ssl.SSLHandshakeException;

interface TLSStrategy {
  Socket TLS(String host, int port, Socket tcpSocket) throws SSLHandshakeException;
}
