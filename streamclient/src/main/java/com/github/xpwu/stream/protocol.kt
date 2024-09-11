package com.github.xpwu.stream

import com.github.xpwu.x.Logger
import kotlin.time.Duration


/**
 *
 * 上层的调用顺序及响应顺序逻辑：
 *
 *                             +--------------------------------------+
 *                             |                                      |
 *                             |                                      v
 *     connect{1} --+--(true)--+---[.async]-->send{n && con=1} ---> close{1}
 *                  |          |                                      ^
 *           (false)|          |-------> onMessage                    |
 *                  |          |             |                        |
 *        <Unit>----+          |          (error) ---- [.async] ----->|
 *                             |                                      |
 *                             +--------> onError ---- [.async] ------+
 *
 *
 *    connect() 与 close() 上层使用方确保只会调用 1 次
 *    send() 会异步调用 n 次，但上层确保并发数为 1
 *    connect() 失败，不会请求/响应任何接口
 *    onMessage 失败 及 onError 会异步调用 close()
 *
 *    连接成功后，任何不能继续通信的情况都以 onError 返回
 *    close() 的调用不触发 onError
 *    connect() 的错误不触发 onError
 *    send() 仅返回本次 send 的错误，不是底层通信的错误，底层通信的错误通过 onError 返回
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

	suspend fun connect(): Pair<Handshake, Error?>
	fun close()
	suspend fun send(content: ByteArray): Error?

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
