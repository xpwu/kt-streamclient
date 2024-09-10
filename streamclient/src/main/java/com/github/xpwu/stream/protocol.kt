package com.github.xpwu.stream

import com.github.xpwu.x.Logger
import kotlin.time.Duration

interface Protocol {
	class Handshake {
		var HearBeatTime: Duration = Duration.INFINITE
		var FrameTimeout: Duration = Duration.INFINITE // 同一帧里面的数据超时
		var MaxConcurrent: Int = Int.MAX_VALUE // 一个连接上的最大并发
		var MaxBytes: Long = 10* 1024 * 1024 // 一帧数据的最大字节数
		var ConnectId: String = "---no_connectId---"
	}

	interface Delegate {
		suspend fun onMessage(message: ByteArray)

		/**
		 * 连接成功后，任何不能继续通信的情况都以 onError 返回，
		 * close() 的调用也可能触发
		 * connect() 的错误不会触发 onError
		 */
		suspend fun onError(error: Error)
	}

	/**
	 * connect() 与 close() 上层使用方确保只会调用一次
	 */
	suspend fun connect(): Pair<Handshake, Error?>
	fun close()

	// 仅是本次 send 的错误，不是底层通信的错误，底层通信的错误通过 onError 返回
	// 需要 send 尽快把数据给到底层就行，而不需等待数据 send 一定结束，所以这里不用 suspend
	fun send(content: ByteArray): Error?

	fun setDelegate(delegate: Delegate)
	fun setLogger(logger: Logger)
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
