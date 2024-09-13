
package com.github.xpwu.stream


import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class Client(internal var protocolCreator: ()->Protocol, internal val logger: Logger = AndroidLogger()) {

	var onPush: suspend (ByteArray)->Unit = {}
	var onPeerClosed: suspend (reason: Error)->Unit = {}

	val flag = Random.nextLong()

	private fun newNet(): Net {
		return Net(protocolCreator
			, {
				logger.Warning("Client[$flag].onPeerClosed", """reason: ${it.message?:"unknown"}""")
				onPeerClosed(it)
			}, {
				logger.Info("Client[$flag].onPush", """size: ${it.size}""")
				onPush(it)
			})
	}

	internal var net = newNet()
	init {
		this.net.logger = logger
	}

	@Synchronized
	internal fun net(): Net {
		if (this.net.isInValid) {
			this.net.close()
			this.net = newNet()
			this.net.logger = logger
		}
		return this.net
	}
}

suspend fun Client.Send(data: ByteArray, headers: Map<String, String>
												, timeout: Duration = 30.seconds): Pair<ByteArray, StError?> {
	val sflag = Random.nextLong()

	logger.Info("Client[$flag].Send[$sflag]:start", """$headers""")
	val net = net()
	val err = net.connect()
	if (err != null) {
		logger.Error("Client[$flag].Send[$sflag]:error", """connect error:$err""")
		return Pair(ByteArray(0), StError(err, true))
	}

	return net.send(data, headers, timeout).let {
		if (it.second != null) {
			logger.Error("Client[$flag].Send[$sflag](connID=${net.connectID}):error"
				, """request error:${it.second}""")
		} else {
			logger.Info("Client[$flag].Send[$sflag](connID=${net.connectID}):end", """data size = ${it.first.size}""")
		}
		return@let it
	}
}

// 下次重连时，使用新的 protocol
fun Client.UpdateProtocol(creator:()->Protocol) {
	protocolCreator = creator
}

/**
 * Close 后，Client 仍可继续使用，下次发送请求时，会自动重连
 * Close() 调用不会触发 onPeerClosed()
 */
@Synchronized
fun Client.Close() {
	this.net.close()
}

suspend fun Client.Recover(): StError? {
	return net().connect()?.let { StError(it, true) }
}


