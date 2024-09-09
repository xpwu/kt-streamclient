
package com.github.xpwu.stream


import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/*
//class ClientOld(vararg options: Option) {
//	internal val clientJava: ClientJava = ClientJava(*options.toOptions())
//	internal val dispatcher = CurrentThreadDispatcher()
//}
//
//suspend fun ClientOld.Send(data: ByteArray, headers: Map<String, String>): Pair<ByteArray, StError?> {
//	return withContext(dispatcher) {
//		suspendCoroutine {
//			clientJava.Send(data, headers, object : ClientJava.ResponseHandler {
//				override fun onFailed(error: java.lang.Error, isConnError: Boolean) {
//					it.resume(Pair<ByteArray, StError?>(ByteArray(0), StError(error, isConnError)))
//				}
//
//				override fun onSuccess(response: ByteArray) {
//					it.resume(Pair<ByteArray, StError?>(response, null))
//				}
//
//			})
//		}
//	}
//}
//
//fun ClientOld.UpdateOptions(vararg options: Option) {
//	clientJava.updateOptions(*options.toOptions())
//}
//
//fun ClientOld.OnPush(block: Pusher) {
//	clientJava.setPushCallback { data -> block(data) }
//}
//
//fun ClientOld.OnPeerClosed(block: () -> Unit) {
//	clientJava.setPeerClosedCallback { block() }
//}
//
//suspend fun ClientOld.Recover(): StError? {
//	return withContext(dispatcher) {
//		suspendCoroutine {
//			clientJava.Recover(object : ClientJava.RecoverHandler {
//				override fun onFailed(error: Error, isConnError: Boolean) {
//					it.resume(StError(error, isConnError))
//				}
//
//				override fun onSuccess() {
//					it.resume(null)
//				}
//
//			})
//		}
//	}
//
//}
*/


class Client(internal var protocolCreator: ()->Protocol, private val logger: Logger = AndroidLogger()) {

	var onPush: suspend (ByteArray)->Unit = {}
	var onPeerClosed: suspend ()->Unit = {}

	internal var net = Net(protocolCreator, {onPeerClosed()}, {onPush(it)})
	init {
		this.net.logger = logger
	}

	@Synchronized
	internal fun net(): Net {
		if (this.net.isInValid) {
			this.net.close()
			this.net = Net(protocolCreator, {onPeerClosed()}, {onPush(it)})
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

// Close 后，Client 仍可继续使用，下次需要网络时，会自动重连
@Synchronized
fun Client.Close() {
	this.net.close()
}

suspend fun Client.Recover(): StError? {
	return net().connect()?.let { StError(it, true) }
}

