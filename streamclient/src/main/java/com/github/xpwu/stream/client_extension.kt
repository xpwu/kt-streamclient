package com.github.xpwu.stream

import com.github.xpwu.stream.lencontent.LenContent
import com.github.xpwu.stream.websocket.Option as WebsocketOption
import com.github.xpwu.stream.websocket.WebSocket
import com.github.xpwu.stream.lencontent.Option as LenContentOption
import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun Client.Companion.withLenContent(vararg options: LenContentOption, logger: Logger = AndroidLogger()): Client {
	return Client(logger) {LenContent(*options) }
}

fun Client.UpdateOptions(vararg options: LenContentOption) {
	this.UpdateProtocol protocol@{ return@protocol LenContent(*options) }
}

fun Client.Companion.withWebsocket(vararg options: WebsocketOption, logger: Logger = AndroidLogger()): Client {
	return Client(logger) { WebSocket(*options) }
}

fun Client.UpdateOptions(vararg options: WebsocketOption) {
	this.UpdateProtocol protocol@{ return@protocol WebSocket(*options) }
}

internal const val reqidKey = "X-Req-Id"

suspend fun Client.SendWithReqId(data: ByteArray, headers: Map<String, String>
												 , timeout: Duration = 30.seconds): Pair<ByteArray, StError?> {

	val muHeaders: MutableMap<String, String> = HashMap()
	for ((i, v) in headers) {
		muHeaders[i] = v
	}
	muHeaders[reqidKey] = UUID.randomUUID().toString()

	return this.Send(data, muHeaders, timeout)
}

