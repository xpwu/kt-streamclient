package com.github.xpwu.stream

import com.github.xpwu.stream.lencontent.LenContent
import com.github.xpwu.stream.lencontent.Option
import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import com.github.xpwu.x.toHex
import java.util.Random
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun Client(vararg options: Option, logger: Logger = AndroidLogger()): Client {
	return Client(logger) {LenContent(*options) }
}

fun Client.UpdateOptions(vararg options: Option) {
	this.UpdateProtocol protocol@{ return@protocol LenContent(*options) }
}

private const val reqidKey = "X-Req-Id"

suspend fun Client.SendWithReqId(data: ByteArray, headers: Map<String, String>
												 , timeout: Duration = 30.seconds): Pair<ByteArray, StError?> {

	val muHeaders: MutableMap<String, String> = HashMap()
	for ((i, v) in headers) {
		muHeaders[i] = v
	}
	muHeaders[reqidKey] = UUID.randomUUID().toString()

	return this.Send(data, muHeaders, timeout)
}

