package com.github.xpwu.stream.websocket

import com.github.xpwu.stream.Protocol
import com.github.xpwu.stream.DummyDelegate
import com.github.xpwu.stream.TimeoutError
import com.github.xpwu.stream.fakehttp.Response
import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import io.ktor.client.HttpClient
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.wss
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readBytes
import io.ktor.client.engine.cio.*
import io.ktor.client.features.websocket.DefaultClientWebSocketSession
import io.ktor.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class WebSocket(vararg options: Option): Protocol {

	private val optValue: OptionValue = OptionValue()

	private val sendMutex: Mutex = Mutex()
	private var outgoing: SendChannel<Frame> = Channel()

	private var wsFrameSize: Long = 1024 * 1024 // ~1M
	private var handshake: Protocol.Handshake = Protocol.Handshake()

	private var logger: Logger = AndroidLogger()
	private var delegate: Protocol.Delegate = DummyDelegate()

	private val flag = Integer.toHexString(Random.nextInt())
	private val connectID: String get() = handshake.ConnectId

	private val scope = CoroutineScope(Dispatchers.IO)

	private var closed = AtomicBoolean(false)

	private var wsClient = HttpClient()

	init {
		for (op in options) {
			op.runner(optValue)
		}
		wsClient = HttpClient(CIO) {
			install(WebSockets)
			engine {
				endpoint {
					connectTimeout = optValue.connectTimeout.inWholeMilliseconds
				}
			}
		}
	}

	override suspend fun close() {
		closed.set(true)
		wsClient.close()
		scope.cancel()
		logger.Debug("WebSocket[$flag]<$connectID>.close", "closed")
	}

	override suspend fun connect(): Pair<Protocol.Handshake, Error?> {
		val retCh: Channel<Pair<Protocol.Handshake, Error?>> = Channel(1)

		logger.Debug("WebSocket[$flag].connect:start", optValue.toString())

		scope.launch {
			try {
				wsClient.wss(optValue.url) {
					val hdframe = incoming.receive()
					if (hdframe !is Frame.Binary) {
						logger.Debug("WebSocket[$flag].connect:error", "the type of the frame is not binary")
						retCh.send(Pair(Protocol.Handshake(), Error("frame type error")))
						return@wss
					}
					if (hdframe.data.size != Protocol.Handshake.StreamLen) {
						logger.Debug("WebSocket[$flag].connect:error", "handshake size error: must be ${Protocol.Handshake.StreamLen}")
						retCh.send(Pair(Protocol.Handshake(), Error("handshake size error")))
						return@wss
					}

					this@WebSocket.wsFrameSize = maxFrameSize
					this@WebSocket.handshake = Protocol.Handshake.Parse(hdframe.data)
					this@WebSocket.outgoing = this.outgoing

					logger.Debug("WebSocket[$flag].connect:handshake", this@WebSocket.handshake.toString())
					retCh.send(Pair(this@WebSocket.handshake, null))

					// setup incoming loop
					setReadingMsg()
				}
			} catch (e: TimeoutCancellationException) {
				logger.Debug("WebSocket[$flag].connect:error", "timeout")
				retCh.send(Pair(Protocol.Handshake(), TimeoutError(e.message?:e.toString())))
			} catch (e: ConnectTimeoutException) {
				logger.Debug("WebSocket[$flag].connect:error", "timeout")
				retCh.send(Pair(Protocol.Handshake(), TimeoutError(e.message?:e.toString())))
			} catch (e: Exception) {
				logger.Debug("WebSocket[$flag].connect:error", e.message?:e.toString())
				retCh.send(Pair(Protocol.Handshake(), Error(e.message?:e.toString())))
			}
		}

		var ret = withTimeoutOrNull(optValue.connectTimeout) {
			return@withTimeoutOrNull retCh.receive()
		}
		if (ret == null) {
			logger.Debug("WebSocket[$flag].connect:error", "timeout")
			ret = Pair(Protocol.Handshake(), TimeoutError("timeout"))
		}

		if (ret.second != null) {
			scope.cancel()
			closed.set(true)
			wsClient.close()

			return ret
		}

		logger.Debug("WebSocket[$flag]<$connectID>.connect:end", "connectID = $connectID")
		return ret
	}

	private suspend fun DefaultClientWebSocketSession.setReadingMsg() {
		logger.Debug("WebSocket[$flag]<$connectID>.receiveMessage:start", "run async loop...")
		try {
			val buffer: MutableList<ByteArray> = ArrayList((this@WebSocket.handshake.MaxBytes / maxFrameSize + 1).toInt())
			while (true) {
				var length = 0
				var frame = incoming.receive()
				length += frame.data.size
				// 不确定能否直接使用底层的 data，所以使用 readBytes()
				buffer.add(frame.readBytes())
				while (!frame.fin && length < this@WebSocket.handshake.MaxBytes) {
					frame = withTimeout(this@WebSocket.handshake.FrameTimeout) {
						return@withTimeout incoming.receive()
					}
					length += frame.data.size
					buffer.add(frame.readBytes())
				}
				if (length >= this@WebSocket.handshake.MaxBytes + Response.MaxNoLoadLen) {
					// error
					logger.Debug("WebSocket[$flag]<$connectID>.receiveMessage:MaxBytes"
						, "error: data(len: $length > maxbytes: ${handshake.MaxBytes}) is Too Large")
					this@WebSocket.delegate.onError(Error("received Too Large data(len=$length), must be less than ${this@WebSocket.handshake.MaxBytes}"))
					break
				}

				// read over
				logger.Debug("WebSocket[$flag]<$connectID>.receiveMessage:read", "read one message")
				if (buffer.size == 1) {
					this@WebSocket.delegate.onMessage(buffer[0])
				} else {
					var count = 0
					for (bu in buffer) {
						count += bu.size
					}
					val msg = ByteArray(count)
					var pos = 0
					for (bu in buffer) {
						System.arraycopy(bu, 0, msg, pos, bu.size)
						pos += bu.size
					}
					this@WebSocket.delegate.onMessage(msg)
				}
				buffer.clear()
			}
		} catch (e: TimeoutCancellationException) {
			// frame timeout
			logger.Debug("WebSocket[$flag]<$connectID>.receiveMessage:timeout", "error: Frame-timeout")
			if (!closed.getAndSet(true)) {
				this@WebSocket.delegate.onError(Error("frame timeout"))
			}
		} catch (e: ClosedReceiveChannelException) {
			// closed
			logger.Debug("WebSocket[$flag]<$connectID>.receiveMessage:closed", "closed")
			if (!closed.getAndSet(true)) {
				this@WebSocket.delegate.onError(Error("closed by peer, reason: ${closeReason.await()}"))
			}
		} catch (e: Exception) {
			logger.Debug("WebSocket[$flag]<$connectID>.receiveMessage:error", e.message?:"unknown")
			if (!closed.getAndSet(true)) {
				this@WebSocket.delegate.onError(Error(e.message))
			}
		}
		logger.Debug("WebSocket[$flag]<$connectID>.receiveMessage:end", "run loop is end")
	}

	override suspend fun send(content: ByteArray): Error? {
		sendMutex.withLock {
			try {
				logger.Debug("WebSocket[$flag]<$connectID>.send:start", "size = ${content.size}")
				var pos = 0
				while (pos < content.size) {
					val fin = pos + wsFrameSize > content.size
					val end = if (fin) content.size else {pos + wsFrameSize.toInt()}
					outgoing.send(Frame.Binary(fin, content.sliceArray(pos ..< end)))
					pos = end
				}
				logger.Debug("WebSocket[$flag]<$connectID>.send:end", "end")
			}catch (e: Exception) {
				logger.Debug("WebSocket[$flag]<$connectID>.send:error", e.message?:"unknown")
				this.delegate.onError(Error(e.message))
			}
		}

		return null
	}

	override fun setDelegate(delegate: Protocol.Delegate) {
		this.delegate = delegate
	}

	override fun setLogger(logger: Logger) {
		this.logger = logger
		val ph = Integer.toHexString(this.hashCode())
		logger.Debug("WebSocket[$flag].new", "flag=$flag, protocol hashcode=$ph")
	}

}
