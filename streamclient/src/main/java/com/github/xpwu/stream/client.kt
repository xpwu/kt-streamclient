
package com.github.xpwu.stream

import com.github.xpwu.x.CurrentThreadDispatcher
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Client(vararg options: Option) {
	internal val clientJava: ClientJava = ClientJava(*options.toOptions())
	internal val dispatcher = CurrentThreadDispatcher()
}

class StError(internal val err: Error, internal val isConnError: Boolean)

val StError.RawError
	get() = err

val StError.IsConnError
	get() = isConnError


typealias Response = ByteArray

suspend fun Client.Send(data: ByteArray, headers: Map<String, String>): Pair<Response, StError?> {
	return withContext(dispatcher) {
		suspendCoroutine {
			clientJava.Send(data, headers, object : ClientJava.ResponseHandler {
				override fun onFailed(error: java.lang.Error, isConnError: Boolean) {
					it.resume(Pair<Response, StError?>(ByteArray(0), StError(error, isConnError)))
				}

				override fun onSuccess(response: ByteArray) {
					it.resume(Pair<Response, StError?>(response, null))
				}

			})
		}
	}
}

fun Client.UpdateOptions(vararg options: Option) {
	clientJava.updateOptions(*options.toOptions())
}

fun Client.OnPush(block: (ByteArray) -> Unit) {
	clientJava.setPushCallback { data -> block(data) }
}

fun Client.OnPeerClosed(block: () -> Unit) {
	clientJava.setPeerClosedCallback { block() }
}

suspend fun Client.Recover(): StError? {
	return withContext(dispatcher) {
		suspendCoroutine {
			clientJava.Recover(object : ClientJava.RecoverHandler {
				override fun onFailed(error: Error, isConnError: Boolean) {
					it.resume(StError(error, isConnError))
				}

				override fun onSuccess() {
					it.resume(null)
				}

			})
		}
	}

}
