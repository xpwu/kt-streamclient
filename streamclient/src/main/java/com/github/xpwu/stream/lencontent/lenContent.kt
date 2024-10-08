package com.github.xpwu.stream.lencontent

import com.github.xpwu.stream.DummyDelegate
import com.github.xpwu.stream.Protocol
import com.github.xpwu.stream.TimeoutError
import com.github.xpwu.stream.fakehttp.Response
import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Host2Net
import com.github.xpwu.x.Logger
import com.github.xpwu.x.Net2Host
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Objects
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

private fun nullOutputStream(): OutputStream {
	return object : OutputStream() {
		@Volatile
		private var closed = false

		private fun ensureOpen() {
			if (closed) {
				throw IOException("Stream closed")
			}
		}

		override fun write(b: Int) {
			ensureOpen()
		}

		override fun write(b: ByteArray, off: Int, len: Int) {
			Objects.checkFromIndexSize(off, len, b.size)
			ensureOpen()
		}

		override fun close() {
			closed = true
		}
	}
}

/**
 *
 * LenContent protocol:
 *
 *     1, handshake protocol:
 *
 *                                 client ------------------ server
 *                                 |                          |
 *                                 |                          |
 *                                 ABCDEF (A^...^F = 0xff) --->  check(A^...^F == 0xff) --- N--> over
 *                                 (A is version)
 *                                 |                          |
 *                                 |                          |Y
 *                                 |                          |
 *          version 1:   set client heartbeat  <----- HeartBeat_s (2 bytes, net order)
 *          version 2:       set config     <-----  HeartBeat_s | FrameTimeout_s | MaxConcurrent | MaxBytes | connect id
 *                                                   HeartBeat_s: 2 bytes, net order
 *                                                   FrameTimeout_s: 1 byte
 *                                                   MaxConcurrent: 1 byte
 *                                                   MaxBytes: 4 bytes, net order
 *                                                   connect id: 8 bytes, net order
 *                                 |                          |
 *                                 |                          |
 *                                 |                          |
 *                                 data      <-------->       data
 *
 *
 *     2, data protocol:
 *        1) length | content
 *        length: 4 bytes, net order; length=sizeof(content)+4; length=0 => heartbeat
 *
 */

class LenContent(vararg options: Option) : Protocol {

	internal val optValue: OptionValue = OptionValue()
	internal var logger: Logger = AndroidLogger()
	internal var delegate: Protocol.Delegate = DummyDelegate()

	internal val heartbeatStop: Channel<Boolean> = Channel(UNLIMITED)

	internal var socket = Socket()
	internal val outputMutex: Mutex = Mutex()
	internal var outputStream = nullOutputStream()

	internal var handshake: Protocol.Handshake = Protocol.Handshake()
	internal val connectID: String get() = handshake.ConnectId

	internal val flag = Integer.toHexString(Random.nextInt())

	internal val daemon = CoroutineScope(CoroutineName("LenContent.daemon") + Dispatchers.IO)

	init {
		for (op in options) {
			op.runner(optValue)
		}
	}

	override suspend fun connect(): Pair<Protocol.Handshake, Error?> {
		return _connect()
	}

	override suspend fun close() {
		_close()
	}

	override suspend fun send(content: ByteArray): Error? {
		return _send(content)
	}

	override fun setDelegate(delegate: Protocol.Delegate) {
		this.delegate = delegate
	}

	override fun setLogger(logger: Logger) {
		this.logger = logger
		val ph = Integer.toHexString(this.hashCode())
		logger.Debug("LenContent[$flag].new", "flag=$flag, protocol hashcode=$ph")
	}
}

private fun handshakeReq(): ByteArray {
	val handshake = ByteArray(6)
	Random.nextBytes(handshake)

	// version is 2
	handshake[0] = 2
	handshake[5] = 0xff.toByte()
	for (i in 0..4) {
		handshake[5] = (handshake[5].toInt() xor (handshake[i]).toInt()).toByte()
	}

	return handshake
}

private fun LenContent.readHandshake(inputStream: InputStream): Pair<Protocol.Handshake, Error?> {
	var pos = 0
	val handshake = ByteArray(Protocol.Handshake.StreamLen)
	while (handshake.size - pos != 0) {
		val n = inputStream.read(handshake, pos, handshake.size - pos)
		if (n <= 0) {
			logger.Debug("LenContent[$flag]._connect:readHandshake", "error: n<=0, maybe connection closed by peer")
			return Pair(Protocol.Handshake(), Error("read handshake error(n<=0), maybe connection closed by peer"))
		}

		pos += n
	}

	this.handshake = Protocol.Handshake.Parse(handshake)

	logger.Debug("LenContent[$flag]<$connectID>.readHandshake:handshake", this.handshake.toString())

	return Pair(this.handshake, null)
}

private fun LenContent.receiveInputStream() {
	daemon.launch {
		val inputStream: InputStream
		try {
			inputStream = socket.getInputStream()
		} catch (e: SocketException) {
			logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:getInputStream", "error --- ${e.message}")
			this@receiveInputStream.delegate.onError(Error(e.message?:"get inputstream error, maybe connection closed by peer"))
			return@launch
		} catch (e: IOException) {
			logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:getInputStream", "error --- ${e.message}")
			this@receiveInputStream.delegate.onError(Error(e.message))
			return@launch
		} catch (e: Exception) {
			logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:getInputStream", "error --- $e")
			this@receiveInputStream.delegate.onError(Error(e.toString()))
			return@launch
		}

		logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:start", "run async loop...")
		while (!socket.isClosed && socket.isConnected && isActive) {
			var heartbeatTimeout = true
			try {
				val lengthB = ByteArray(4)
				var pos = 0

				heartbeatTimeout = true
				socket.soTimeout = handshake.HearBeatTime.times(2).inWholeMilliseconds.toInt()
				// 先读一个，表示有数据了
				var n: Int = inputStream.read(lengthB, pos, 1)
				if (n <= 0) {
					logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:read-1", "error: n<=0")
					if (!socket.isClosed) {
						this@receiveInputStream.delegate.onError(Error("inputstream read-1 error, maybe connection closed by peer"))
					}
					break
				}
				pos += n

				heartbeatTimeout = false
				socket.soTimeout = handshake.FrameTimeout.inWholeMilliseconds.toInt()
				while (4 - pos != 0 && n > 0) {
					n = inputStream.read(lengthB, pos, 4 - pos)
					pos += n
				}
				if (n <= 0) {
					logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:read-4", "error: n<=0")
					if (!socket.isClosed) {
						this@receiveInputStream.delegate.onError(Error("inputstream read-4 error, maybe connection closed by peer"))
					}
					break
				}

				pos = 0
				var length = (((0xff and lengthB[0].toInt()).toLong() shl 24)
					+ ((0xff and lengthB[1].toInt()) shl 16)
					+ ((0xff and lengthB[2].toInt()) shl 8)
					+ ((0xff and lengthB[3].toInt())))
				if (length == 0L) { // heartbeat
					logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:Heartbeat", "receive heartbeat from server")
					continue
				}

				length -= 4
				// 出现这种情况，很可能是协议出现问题了，而不能单纯的认为是本次请求的问题
				if (length > this@receiveInputStream.handshake.MaxBytes + Response.MaxNoLoadLen) {
					logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:MaxBytes"
						, "error: data(len: $length > maxbytes: ${handshake.MaxBytes}) is Too Large")
					this@receiveInputStream.delegate.onError(
						Error("""received Too Large data(len=$length), must be less than ${this@receiveInputStream.handshake.MaxBytes}"""))
					break
				}

				val data = ByteArray(length.toInt())
				while (length - pos != 0L && n > 0) {
					socket.soTimeout = handshake.FrameTimeout.inWholeMilliseconds.toInt()
					n = inputStream.read(data, pos, length.toInt() - pos)
					pos += n
				}
				if (n <= 0) {
					logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:read-n", "error: n<=0")
					if (!socket.isClosed) {
						this@receiveInputStream.delegate.onError(Error("inputstream read-n error, maybe connection closed by peer"))
					}
					break
				}

				logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:read", "read one message")
				this@receiveInputStream.delegate.onMessage(data)

			} catch (e: SocketTimeoutException) {
				logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:timeout", "error: ${if(heartbeatTimeout)"Heartbeat" else "Frame"}-timeout")
				this@receiveInputStream.delegate.onError(
					Error("""LenContent.receiveInputStream---${if(heartbeatTimeout)"Heartbeat" else "Frame"}-timeout: ${e.message?:e.toString()}"""))
				break
			} catch (e: SocketException) {
				logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:error", e.message?:"unknown")
				this@receiveInputStream.delegate.onError(Error(e.message?:"LenContent.receiveInputStream---SocketException"))
				break
			} catch (e: Exception) {
				logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:error", e.message?:"unknown")
				this@receiveInputStream.delegate.onError(Error(e.toString()))
				break
			}
		}
		logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:end", "run loop is end")
	}
}

internal suspend fun LenContent._connect(): Pair<Protocol.Handshake, Error?> {
	val r = withTimeoutOrNull(optValue.connectTimeout) {
		withContext(Dispatchers.IO) {
			logger.Debug("LenContent[$flag]._connect:start", optValue.toString())
			try {
				// connect 没有明确的 timeout 类型返回，所以让 connect 比 withTimeoutOrNull 多一秒
				// withTimeoutOrNull 先于 connect 超时
				socket.connect(InetSocketAddress(optValue.host, optValue.port)
					, optValue.connectTimeout.plus(1.seconds).inWholeMilliseconds.toInt())

				val tlsRes = optValue.tls(optValue.host, optValue.port, socket)
				if (tlsRes.second != null) {
					logger.Debug("LenContent[$flag]._connect:tls"
						, "error: ${tlsRes.second!!.message?:"unknown"}")
					return@withContext Pair(Protocol.Handshake(), tlsRes.second)
				}
				socket = tlsRes.first

				outputMutex.lock()
				outputStream = socket.getOutputStream()
				outputMutex.unlock()

				logger.Debug("LenContent[$flag]._connect:handshake", "write handshake ... ")
				// 发握手数据
				outputStream.write(handshakeReq())
				outputStream.flush()

				return@withContext readHandshake(socket.getInputStream())

			} catch (e: Exception) {
				return@withContext Pair(Protocol.Handshake(), Error(e.message?:e.toString()))
			}
		}
	}

	r?.let {
		if (it.second != null) {
			logger.Debug("LenContent[$flag]._connect:error", it.second!!.message?:"unknown")
			return it
		}
		receiveInputStream()
		setOutputHeartbeat()

		logger.Debug("LenContent[$flag]<$connectID>._connect:end", "connectID = $connectID")
		return it
	}

	// timeout
	logger.Debug("LenContent[$flag]._connect:timeout"
		, "timeout(${optValue.connectTimeout.inWholeSeconds}s)")
	return Pair(Protocol.Handshake()
		, TimeoutError("""LenContent._connect: timeout(${optValue.connectTimeout.inWholeSeconds}s)"""))
}

internal fun LenContent._close() {
	try {
		if (socket.isClosed) {
			return
		}
		logger.Debug("LenContent[$flag]<$connectID>._close", "closed")
		heartbeatStop.close()
		daemon.cancel("LenContent.close")
		socket.close()
	} catch (e: Exception) {
		logger.Error("LenContent[$flag]<$connectID>._close:error", e.toString())
	}
}

/**
 * stop 后必须调用 set
 * heartbeat 后必须再次调用
 * 可以多发 heartbeat，但不能不发 heartbeat
 */
private suspend fun LenContent.stopOutputHeartbeat() {
	try {
		logger.Debug("LenContent[$flag]<$connectID>.outputHeartbeatTimer", "will stop")
		heartbeatStop.send(true)
	} catch (e: ClosedReceiveChannelException) {
		return
	}
}

private fun LenContent.setOutputHeartbeat() {
	daemon.launch {
		logger.Debug("LenContent[$flag]<$connectID>.outputHeartbeatTimer:set", "set")
		val ret = withTimeoutOrNull(this@setOutputHeartbeat.handshake.HearBeatTime) {
			try {
				heartbeatStop.receive()
			} catch (e: ClosedReceiveChannelException) {
				return@withTimeoutOrNull true
			}
		}

		// stopped
		ret?.let {
			logger.Debug("LenContent[$flag]<$connectID>.outputHeartbeatTimer:stopped", "stopped")
			return@launch
		}

		// timeout
		logger.Debug("LenContent[$flag]<$connectID>.outputHeartbeatTimer:send", "send heartbeat to server")
		outputMutex.lock()
		try {
			outputStream.write(ByteArray(4){0})
		}catch (e: Exception) {
			this@setOutputHeartbeat.delegate.onError(Error(e.message?:e.toString()))
		}finally {
			outputMutex.unlock()
		}

		setOutputHeartbeat()
	}
}

internal suspend fun LenContent._send(content: ByteArray): Error? {
	return withContext(Dispatchers.IO) {
		val len = ByteArray(4)
		val length = content.size.toLong() + 4
		Host2Net(length, len)

		stopOutputHeartbeat()
		outputMutex.lock()
		try {
			logger.Debug("LenContent[$flag]<$connectID>._send:start", "frameBytes = $length")

			outputStream.write(len)
			outputStream.write(content)
			logger.Debug("LenContent[$flag]<$connectID>._send:end", "end")

			return@withContext null
		} catch (e: Exception) {
			logger.Debug("LenContent[$flag]<$connectID>._send:error", e.message?:"unknown")
			this@_send.delegate.onError(Error(e.message?:e.toString()))
			return@withContext  Error(e.message?:e.toString())
		} finally {
			outputMutex.unlock()
			setOutputHeartbeat()
		}
	}
}

