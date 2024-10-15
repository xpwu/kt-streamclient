
package com.github.xpwu.stream


import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class Client(internal val logger: Logger = AndroidLogger(), internal var protocolCreator: ()->Protocol) {

	var onPush: suspend (ByteArray)->Unit = {}
	var onPeerClosed: suspend (reason: Error)->Unit = {}

	internal val flag = Integer.toHexString(Random.nextInt())
	private var net: Net

	companion object;

	init {
		logger.Info("Client[$flag].new", "flag=$flag")
		net = newNet()
	}

	private fun newNet(): Net {
		return Net(logger, protocolCreator
			, {
				logger.Warning("Client[$flag].onPeerClosed", """reason: ${it.message?:"unknown"}""")
				onPeerClosed(it)
			}, {
				logger.Info("Client[$flag].onPush", """size: ${it.size}""")
				onPush(it)
			})
	}

	@Synchronized
	internal fun net(): Net {
		if (this.net.isInValid) {
			this.net.close()
			this.net = newNet()
		}
		return this.net
	}

	/**
	 * Close 后，Client 仍可继续使用，下次发送请求时，会自动重连
	 * Close() 调用不会触发 onPeerClosed()
	 * Close() 与 其他接口没有明确的时序关系，Close() 调用后，也可能会出现 Send() 的调用返回 或者 onPeerClosed()
	 * 		但此时的 onPeerClosed() 并不是因为 Close() 而触发的。
	 */
	@Synchronized
	fun Close() {
		logger.Info("Client[$flag].close", "closed by self")
		this.net.close()
	}
}

suspend fun Client.Send(data: ByteArray, headers: Map<String, String>
												, timeout: Duration = 30.seconds): Pair<ByteArray, StError?> {
	val sflag = headers[reqidKey] ?: Integer.toHexString(Random.nextInt())

	logger.Info("Client[$flag].Send[$sflag]:start", """$headers, request size = ${data.size}""")

	val net = net()
	val err = net.connect()
	if (err != null) {
		logger.Error("Client[$flag].Send[$sflag]:error", """connect error: ${err.message}""")
		return Pair(ByteArray(0)
			, if (err is TimeoutError) TimeoutStError(err, true) else StError(err, true))
	}

	val ret = net.send(data, headers, timeout)
	if (ret.second == null) {
		logger.Info("Client[$flag].Send[$sflag](connID=${net.connectID}):end", """response size = ${ret.first.size}""")
		return ret
	}
	if (! ret.second!!.IsConnError) {
		logger.Error("Client[$flag].Send[$sflag](connID=${net.connectID}):error"
			, """request error: ${ret.second}""")
		return ret
	}

	// sending --- conn error:  retry
	logger.Debug("Client[$flag].Send[$sflag]:retry", "retry-1")

	val net2 = net()
	val err2 = net2.connect()
	if (err2 != null) {
		logger.Error("Client[$flag].Send[$sflag]:error", """connect error: $err2""")
		return Pair(ByteArray(0)
			, if (err2 is TimeoutError) TimeoutStError(err2, true) else StError(err2, true))
	}

	return net2.send(data, headers, timeout).let {
		if (it.second != null) {
			logger.Error("Client[$flag].Send[$sflag](connID=${net.connectID}):error"
				, """request error: ${ret.second}""")
		} else {
			logger.Info("Client[$flag].Send[$sflag](connID=${net.connectID}):end", """response size = ${it.first.size}""")
		}
		return@let it
	}
}

// 下次重连时，使用新的 protocol
fun Client.UpdateProtocol(creator:()->Protocol) {
	protocolCreator = creator
}

suspend fun Client.Recover(): StError? {
	return net().connect()?.let {
		if (it is TimeoutError) TimeoutStError(it, true) else StError(it, true)
	}
}


