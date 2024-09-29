package com.github.xpwu.stream

import com.github.xpwu.stream.fakehttp.parse
import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.github.xpwu.stream.fakehttp.Response as FakeHttpResponse
import com.github.xpwu.stream.fakehttp.Request as FakeHttpRequest

private const val reqIdStart: Long = 10

private typealias RequestChannel = Channel<Pair<FakeHttpResponse, StError?>>

private class SyncAllRequest(permits: Int = 3) {
	private val reqMutex: Mutex = Mutex()
	private val allRequests: MutableMap<Long, RequestChannel> = HashMap()

	private var semaphore: Semaphore = Semaphore(permits)

	var permits = permits
		set(value) {
			semaphore = Semaphore(value)
			field = value
		}


	// channel 必须在 SyncAllRequest 的控制下，所以 Add 获取的只能 receive
	// 要 send 就必须通过 remove 获取
	suspend fun Add(reqId: Long): ReceiveChannel<Pair<FakeHttpResponse, StError?>> {
		semaphore.acquire()
		reqMutex.lock()
		try {
			val ch = Channel<Pair<FakeHttpResponse, StError?>>(1)
			allRequests[reqId] = ch
			return ch

		} finally {
			reqMutex.unlock()
		}
	}

	// 可以用同一个 reqid 重复调用
	suspend fun Remove(reqId: Long): SendChannel<Pair<FakeHttpResponse, StError?>>? {
		reqMutex.lock()
		try {
			val ret = allRequests.remove(reqId)
			if (ret != null && semaphore.availablePermits < permits) {
				semaphore.release()
			}
			return ret

		} finally {
			reqMutex.unlock()
		}
	}

	suspend fun ClearAllWith(ret: Pair<FakeHttpResponse, StError?>) {
		reqMutex.lock()
		try {
			for ((_, ch) in allRequests) {
				try {
					ch.send(ret)
					ch.close()
				}catch (e: Exception) {
					// nothing to do
				}
			}
			allRequests.clear()
			while (semaphore.availablePermits < permits) {
				semaphore.release()
			}
		} finally {
			reqMutex.unlock()
		}
	}
}

/**
 *
 *    NotConnect  --->  Connecting  ---> Connected ---> Invalidated
 *                          |                                ^
 *                          |                                |
 *                          |________________________________|
 *
 */
private sealed class State {
	data object NotConnect: State()
	data object Connected: State()
	data class Invalidated(val err: Error): State()

	override fun toString(): String {
		return when(this) {
			is Invalidated -> "Invalidated"
			NotConnect -> "NotConnect"
			Connected -> "Connected"
		}
	}
}

internal class Net internal constructor(private val logger: Logger = AndroidLogger()
	, protocolCreator: ()->Protocol
	, private val onPeerClosed: suspend (Error)->Unit
	, private val onPush: suspend (ByteArray)->Unit): Protocol.Delegate {

	internal val isInValid
		get() = state is State.Invalidated

	private var handshake: Protocol.Handshake = Protocol.Handshake()

	private val connLocker:Mutex = Mutex()
	private var state: State = State.NotConnect
	private val protocol: Protocol = protocolCreator()

	private var reqId = reqIdStart
	private val allRequests: SyncAllRequest = SyncAllRequest()

	private val scope = CoroutineScope(CoroutineName("Net"))

	private val flag = Integer.toHexString(Random.nextInt())

	init {
		protocol.setDelegate(this)
		protocol.setLogger(logger)
		val ph = Integer.toHexString(protocol.hashCode())
		logger.Debug("Net[$flag].new", "flag=$flag, protocol.hashcode=$ph")
	}

	protected fun finalize() {
		this.close()
	}

	internal val connectID: String get() = handshake.ConnectId

	// 可重复调用
	internal suspend fun connect(): Error? {

		connLocker.withLock {
			val st = this.state
			if (st == State.Connected) {
				logger.Debug("Net[$flag].connect:Connected", "connID=${connectID}")
				return null
			}
			if (st is State.Invalidated) {
				logger.Debug("Net[$flag].connect<$connectID>:Invalidated", st.err.message ?: "unknown")
				return st.err
			}

			// State.NotConnect
			logger.Debug("Net[$flag].connect:NotConnect", "will connect")
			val connRet = this.protocol.connect()
			connRet.second?.let {
				this.state = State.Invalidated(it)
				logger.Debug("Net[$flag].connect:error", it.message ?: it.toString())
				return it
			}

			// OK
			this.state = State.Connected
			this.handshake = connRet.first
			this.allRequests.permits = this.handshake.MaxConcurrent
			logger.Debug("Net[$flag]<$connectID>.connect:handshake", this.handshake.toString())

			return null
		}
	}

	@Synchronized
	private fun reqId(): Long {
		reqId++
		if (reqId < reqIdStart || reqId > Int.MAX_VALUE) {
			reqId = reqIdStart
		}
		return reqId
	}

	// 如果没有连接成功，直接返回失败
	suspend fun send(data: ByteArray, headers: Map<String, String>
									 , timeout: Duration = 30.seconds): Pair<ByteArray, StError?> {
		// 预判断
		connLocker.withLock {
			this.state.let {
				logger.Debug("Net[$flag]<$connectID>.send:state", """$it --- $headers""")
				if (it is State.Invalidated) {
					return Pair(ByteArray(0), StError(it.err, true))
				}
				if (it != State.Connected) {
					return Pair(ByteArray(0), StError(Error("not connected"), true))
				}
			}
		}

		val reqId = reqId()
		val (request, err) = FakeHttpRequest(data, headers)
		if (err != null) {
			logger.Debug("Net[$flag]<$connectID>.send:FakeHttpRequest", """$headers (reqId:${reqId}) --- error: $err""")
			return Pair(ByteArray(0), StError(err, false))
		}
		request.setReqId(reqId)
		if (request.encodedData.size > handshake.MaxBytes) {
			logger.Debug("Net[$flag]<$connectID>.send:MaxBytes", """$headers (reqId:${reqId}) --- error: Too Large""")
			return Pair(ByteArray(0)
				, StError(Error("""request.size(${request.encodedData.size}) > MaxBytes(${handshake.MaxBytes})"""), false))
		}

		// 在客户端超时也认为是一个请求结束，但是真正的请求并没有结束，所以在服务器看来，仍然占用服务器的一个并发数
		// 因为网络异步的原因，客户端并发数不可能与服务器完全一样，所以这里主要是协助服务器做预控流，按照客户端的逻辑处理即可

		logger.Debug("Net[$flag]<$connectID>.send[$reqId]:request", """$headers (reqId:${reqId})""")

		try {
			val ch = allRequests.Add(reqId)

			val ret = withTimeoutOrNull(timeout) {
				launch(Dispatchers.IO) {
					this@Net.protocol.send(request.encodedData)?.let {
						allRequests.Remove(reqId)?.send(Pair(FakeHttpResponse(), StError(it, false)))
					}
				}
				return@withTimeoutOrNull ch.receive()
			}

			// ret == null: timeout
			if (ret == null) {
				logger.Debug("Net[$flag]<$connectID>.send[$reqId]:Timeout"
					, """$headers (reqId:${reqId}) --- timeout(>${timeout.inWholeSeconds}s)""")
				return Pair(ByteArray(0)
					, TimeoutStError(Error("""request timeout(${timeout.inWholeSeconds}s)"""), false))
			}

			ret.second?.let {
				return Pair(ByteArray(0), it)
			}

			logger.Debug("Net[$flag]<$connectID>.send[$reqId]:response"
				, """$headers (reqId:${reqId}) --- ${ret.first.status}""")

			if (ret.first.status != FakeHttpResponse.Status.Ok) {
				return Pair(ByteArray(0), StError(Error(ret.first.data.toString(Charsets.UTF_8)), false))
			}
			return Pair(ret.first.data, null)

		} finally {
			allRequests.Remove(reqId)
		}
	}

	override suspend fun onMessage(message: ByteArray) {
		val (response, err) = message.parse()
		err?.let {
			logger.Debug("Net[$flag]<$connectID>.onMessage:parse", """error --- ${it.message}:unknown""")
			onError(it)
			return
		}

		if (response.isPush) {
			val pushAck = response.newPushAck()
			pushAck.second?.let {
				logger.Debug("Net[$flag]<$connectID>.onMessage:newPushAck"
					, """error --- ${it.message}:unknown""")
				onError(it)
				return
			}

			scope.launch(Dispatchers.Default) {
				this@Net.onPush(response.data)
			}
			scope.launch(Dispatchers.IO) {
				// ignore error
				this@Net.protocol.send(pushAck.first)?.let {
					logger.Debug("Net[$flag]<$connectID>.onMessage:pushAck"
						, """error --- ${it.message?:it.toString()}""")
				}
			}
			return
		}

		val ch = allRequests.Remove(response.reqID)
		if (ch == null) {
			logger.Warning("Net[$flag]<$connectID>.onMessage:NotFind"
				, """warning: not find request for reqId(${response.reqID})""")
			return
		}

		logger.Debug("Net[$flag]<$connectID>.onMessage:response", """reqId=${response.reqID}""")
		ch.send(Pair(response, null))
	}

	private suspend fun closeAndOldState(error: Error): State {
		val old = connLocker.withLock {
			val old = this.state

			if (this.state is State.Invalidated) {
				return old
			}
			this.state = State.Invalidated(error)
			logger.Debug("Net[$flag]<$connectID>.Invalidated", error.message?:error.toString())

			return@withLock old
		}

		// 所有请求
		allRequests.ClearAllWith(Pair(FakeHttpResponse(), StError(error, true)))

		return old
	}

	override suspend fun onError(error: Error) {
		val oldState = closeAndOldState(error)
		if (oldState == State.Connected) {

			scope.launch(Dispatchers.Default) {
				logger.Debug("Net[$flag]<$connectID>.onError:onPeerClosed", error.message?:error.toString())
				onPeerClosed(error)
			}

			this.protocol.close()
		}
	}

	internal fun close() {

		CoroutineScope(Dispatchers.IO).launch {
			val oldState = closeAndOldState(Error("closed by self"))
			if (oldState == State.Connected) {
				logger.Debug("Net[$flag]<$connectID>.close", "closed, become invalidated")

				this@Net.protocol.close()
			}

			scope.cancel("closed by self")
		}
	}

}

