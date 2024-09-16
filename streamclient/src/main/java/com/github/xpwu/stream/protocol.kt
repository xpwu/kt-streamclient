package com.github.xpwu.stream

import com.github.xpwu.x.Logger
import com.github.xpwu.x.Net2Host
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


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

		companion object {
			/**
			 * ```
			 * HeartBeat_s | FrameTimeout_s | MaxConcurrent | MaxBytes | connect id
			 * HeartBeat_s: 2 bytes, net order
			 * FrameTimeout_s: 1 byte
			 * MaxConcurrent: 1 byte
			 * MaxBytes: 4 bytes, net order
			 * connect id: 8 bytes, net order
			 * ```
			 */

			const val StreamLen = 2 + 1 + 1 + 4 + 8

			fun Parse(handshake: ByteArray): Handshake {
				assert(handshake.size >= StreamLen)

				val ret = Handshake()
				ret.HearBeatTime = (((0xff and handshake[0].toInt()) shl 8) + (0xff and handshake[1].toInt())).seconds
				ret.FrameTimeout = handshake[2].toInt().seconds // DurationJava(handshake[2] * DurationJava.Second)
				ret.MaxConcurrent = handshake[3].toInt()
				ret.MaxBytes = Net2Host(handshake, 4, 8)
				val id1 = Net2Host(handshake, 8, 12)
				val id2 = Net2Host(handshake, 12, 16)
				ret.ConnectId = String.format("%08x", id1) + String.format("%08x", id2)

				return ret
			}
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
		", HearBeatTime: ${this.HearBeatTime}, MaxBytes/frame: ${this.MaxBytes}, FrameTimeout: ${this.FrameTimeout}}"
}

class DummyDelegate(): Protocol.Delegate {
	override suspend fun onMessage(message: ByteArray) {
		TODO("onMessage")
	}
	override suspend fun onError(error: Error) {
		TODO("onError")
	}

}

