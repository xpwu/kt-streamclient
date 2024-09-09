package com.github.xpwu.stream

import com.github.xpwu.x.Logger
import kotlin.time.Duration

interface Protocol {
	class Handshake {
		var HearBeatTime: Duration = Duration.INFINITE
		var FrameTimeout: Duration = Duration.INFINITE // 同一帧里面的数据延时
		var MaxConcurrent: Int = Int.MAX_VALUE // 一个连接上的最大并发
		var MaxBytes: Long = 1024 * 1024 // 一次数据发送的最大字节数
		var ConnectId: String = "---no_connectId---"
	}

	interface Delegate {
		suspend fun onMessage(message: ByteArray)

		// 连接成功后，任何不能继续通信的情况都以 onError 返回
		// connect() 的错误不触发 onError
		// close() 的调用不触发 onError
		suspend fun onError(error: Error)
	}

	suspend fun connect(): Pair<Handshake, Error?>
	fun close()

	fun send(content: ByteArray)

	var delegate: Delegate
	var logger: Logger
}

internal fun Protocol.Handshake.Info(): String {
	return """
		ConnectId: ${this.ConnectId}
		MaxConcurrent: ${this.MaxConcurrent}
		HearBeatTime: ${this.HearBeatTime}
		MaxBytes/frame: ${this.MaxBytes}
		FrameTimeout: ${this.FrameTimeout}
	""".replaceIndent("	---")
}
