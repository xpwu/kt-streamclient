
package com.github.xpwu.stream


import com.github.xpwu.stream.lencontent.LenContent
import com.github.xpwu.stream.lencontent.Option
import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


class Client(internal var protocolCreator: ()->Protocol, private val logger: Logger = AndroidLogger()) {

	var onPush: suspend (ByteArray)->Unit = {}
	var onPeerClosed: suspend (reason: Error)->Unit = {}

	internal var net = Net(protocolCreator, {onPeerClosed(it)}, {onPush(it)})
	init {
		this.net.logger = logger
	}

	@Synchronized
	internal fun net(): Net {
		if (this.net.isInValid) {
			this.net.close()
			this.net = Net(protocolCreator, {onPeerClosed(it)}, {onPush(it)})
			this.net.logger = logger
		}
		return this.net
	}
}

suspend fun Client.Send(data: ByteArray, headers: Map<String, String>
												, timeout: Duration = 30.seconds): Pair<ByteArray, StError?> {
	val net = net()
	val err = net.connect()
	if (err != null) {
		return Pair(ByteArray(0), StError(err, true))
	}

	return net.send(data, headers, timeout)
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


