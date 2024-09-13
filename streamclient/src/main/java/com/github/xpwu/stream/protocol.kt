package com.github.xpwu.stream

import com.github.xpwu.x.Logger
import kotlin.time.Duration


/**
 *
 * 上层的调用 Protocol 及响应 Delegate 的时序逻辑：
 *
 *                             +-----------------------------------+
 *                             |                                   |
 *                             |                                   v
 *     connect{1} --+--(true)--+---[.async]--->send{n} ------> close{1}
 *                  |          |                                   ^
 *           (false)|          |-------> onMessage                 |
 *                  |          |             |                     |
 *        <Unit>----+          |          (error) --- [.async] --->|
 *                             |                                   |
 *                             +--------> onError --- [.async] ----+
 *
 *
 *    Protocol.connect() 与 Protocol.close() 上层使用方确保只会调用 1 次
 *    Protocol.connect() 失败，不会请求/响应任何接口
 *    Protocol.send() 会异步并发地调用 n 次，Protocol.send() 执行的时长不会让调用方挂起等待
 *    在上层明确调用 Protocol.close() 后，才不会调用 Protocol.send()
 *    Delegate.onMessage() 失败 及 Delegate.onError() 会异步调用 Protocol.close()
 *
 *    连接成功后，任何不能继续通信的情况都以 Delegate.onError() 返回
 *    Delegate.close() 的调用不触发 Delegate.onError()
 *    Delegate.connect() 的错误不触发 Delegate.onError()
 *    Delegate.send() 仅返回本次 Delegate.send() 的错误，
 *       不是底层通信的错误，底层通信的错误通过 Delegate.onError() 返回
 *
 */

interface Protocol {
	class Handshake {
		var HearBeatTime: Duration = Duration.INFINITE
		var FrameTimeout: Duration = Duration.INFINITE // 同一帧里面的数据超时
		var MaxConcurrent: Int = Int.MAX_VALUE // 一个连接上的最大并发
		var MaxBytes: Long = 10* 1024 * 1024 // 一帧数据的最大字节数
		var ConnectId: String = "---no_connectId---"

		override fun toString(): String {
			return Info()
		}
	}

	interface Delegate {
		suspend fun onMessage(message: ByteArray)
		suspend fun onError(error: Error)
	}

	// timeout: return TimeoutError
	suspend fun connect(): Pair<Handshake, Error?>
	suspend fun close()
	suspend fun send(content: ByteArray): Error?

	fun setDelegate(delegate: Delegate)
	fun setLogger(logger: Logger)
}

internal fun Protocol.Handshake.Info(): String {
	return "handshake info: {ConnectId: ${this.ConnectId}, MaxConcurrent: ${this.MaxConcurrent}" +
		", HearBeatTime: ${this.HearBeatTime}, MaxBytes/frame: ${this.MaxBytes}, FrameTimeout: ${this.FrameTimeout} }"
}

