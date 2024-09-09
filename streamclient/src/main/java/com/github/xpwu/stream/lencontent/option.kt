package com.github.xpwu.stream.lencontent

import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class OptionValue {
	var host: String = "127.0.0.1"
	var port: Int = 10000
	var tls: (host: String, port: Int, tcpSocket: Socket)->Pair<Socket, Error?>
		// default: no tls
		=  {_: String, _: Int, tcpSocket: Socket -> Pair(tcpSocket, null)}
	var connectTimeout: Duration = 30.seconds
}

//typealias Option = (OptionValue)->Unit

class Option internal constructor(internal val runner: (OptionValue)->Unit)

fun Host(host: String): Option {
	return Option{
		value -> value.host = host
	}
}

fun Port(port: Int): Option {
	return Option{
			value -> value.port = port
	}
}

fun TLS(): Option {
	return Option{
		value -> value.tls =
		socket@ { host, port, tcpSocket ->
			val sslSocket: SSLSocket
			try {
				val context = SSLContext.getInstance("TLS")
				context.init(null, null, null)
				val factory = context.socketFactory
				sslSocket = factory.createSocket(tcpSocket, host, port, true) as SSLSocket
				sslSocket.startHandshake()
			} catch (e: Exception) {
				return@socket Pair(tcpSocket, Error(e.message))
			}

			val sslSession = sslSocket.session

			// 使用默认的HostnameVerifier来验证主机名
			val hv = HttpsURLConnection.getDefaultHostnameVerifier()
			if (!hv.verify(host, sslSession)) {
				return@socket Pair(tcpSocket, Error("Expected " + host + ", got " + sslSession.peerPrincipal))
			}

			return@socket Pair(sslSocket, null)
		}
	}
}

fun TLS(strategy: (host: String, port: Int , tcpSocket: Socket)->Pair<Socket, Error?>): Option {
	return Option{
			value -> value.tls = strategy
	}
}

fun ConnectTimeout(duration: Duration): Option {
	return Option{
			value -> value.connectTimeout = duration
	}
}

