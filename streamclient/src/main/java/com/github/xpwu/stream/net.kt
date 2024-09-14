package com.github.xpwu.stream

import com.github.xpwu.stream.fakehttp.parse
import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
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

	suspend fun Add(reqId: Long): RequestChannel {
		try {
			semaphore.acquire()
			reqMutex.lock()
			val ch = Channel<Pair<FakeHttpResponse, StError?>>(1)
			allRequests[reqId] = ch
			return ch

		} finally {
			reqMutex.unlock()
		}
	}

	// 可以用同一个 reqid 重复调用
	suspend fun Remove(reqId: Long): RequestChannel? {
		try {
			reqMutex.lock()
			val ret = allRequests.remove(reqId)
			if (ret != null && semaphore.availablePermits != 0) {
				semaphore.release()
			}
			return ret

		} finally {
			reqMutex.unlock()
		}
	}

	suspend fun ClearAllWith(ret: Pair<FakeHttpResponse, StError?>) {
		try {
			reqMutex.lock()
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
	data object Connecting: State()
	data object Connected: State()
	data class Invalidated(val err: Error): State()

	override fun toString(): String {
		return when(this) {
			is Invalidated -> "Invalidated"
			NotConnect -> "NotConnect"
			Connecting -> "Connecting"
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

	private val connLocker: ReadWriteLock = ReentrantReadWriteLock()
	private val protocol: Protocol = protocolCreator()
	private var state: State = State.NotConnect
	private val waitingConnects: MutableList<SendChannel<Error?>> = ArrayList()

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
		connLocker.readLock().lock()
		val nowSt = this.state
		connLocker.readLock().unlock()
		if (nowSt == State.Connected) {
			logger.Debug("Net[$flag].connect:Connected", "connID=${connectID}")
			return null
		}
		if (nowSt is State.Invalidated) {
			logger.Debug("Net[$flag].connect<$connectID>:Invalidated", nowSt.err.message?:"unknown")
			return nowSt.err
		}

		try {
			connLocker.writeLock().lock()

			when (val it = this.state) {
				State.Connected -> return null
				is State.Invalidated -> return it.err
				State.Connecting -> {
					// waiting
					val ch = Channel<Error?>(1)
					waitingConnects.add(ch)
					logger.Debug("Net[$flag].connect:Connecting", "wait for being connected")
					return ch.receive()
				}
				State.NotConnect -> this.state = State.Connecting
			}
		} finally {
			connLocker.writeLock().unlock()
		}

		// State.NotConnect
		logger.Debug("Net[$flag].connect:NotConnect", "will connect")
		val connRet = this.protocol.connect()
		val err = connRet.second
		if (err == null) {
			this.handshake = connRet.first
			this.allRequests.permits = this.handshake.MaxConcurrent
			logger.Debug("Net[$flag]<$connectID>.connect:handshake" , this.handshake.toString())
		} else {
			logger.Debug("Net[$flag].connect:error", err.message?:err.toString())
		}

		try {
			connLocker.writeLock().lock()
			this.state = if (err == null) State.Connected else State.Invalidated(err)
			for (ch in waitingConnects) {
				ch.send(connRet.second)
				ch.close()
			}
			waitingConnects.clear()

		} finally {
			connLocker.writeLock().unlock()
		}

		return connRet.second
	}

	@Synchronized
	private fun reqId(): Long {
		reqId++
		if (reqId < reqIdStart || reqId > Int.MAX_VALUE) {
			reqId = reqIdStart
		}
		return reqId
	}

	private suspend fun sendSafely(data: ByteArray): StError? {
		try {
			connLocker.readLock().lock()
			logger.Debug("Net[$flag]<$connectID>.sendSafely:state", state.toString())
			// 发送前，需要再次判断状态，才能确保 send 的调用符合 protocol 的要求
			// 另外，也防止 onError 已经执行的情况下，再 send，可能会造成没有 respond 的情况，而被迫等待超时
			state.let {
				if (it is State.Invalidated) return StError(it.err, true)
				if (it != State.Connected) return StError(Error("not connected"), true)
			}

			return this.protocol.send(data)?.let { StError(it, false) }

		} catch(e: Exception) {
			logger.Debug("Net[$flag]<$connectID>.sendSafely:error", e.message?:e.toString())
			return StError(Error(e.message?:e.toString()), true)
		} finally {
			connLocker.readLock().unlock()
		}
	}

	// 如果没有连接成功，直接返回失败
	suspend fun send(data: ByteArray, headers: Map<String, String>
									 , timeout: Duration = 30.seconds): Pair<ByteArray, StError?> {
		connLocker.readLock().lock()
		val nowSt = this.state
		connLocker.readLock().unlock()
		logger.Debug("Net[$flag]<$connectID>.send:state", """$state --- $headers""")
		if (nowSt is State.Invalidated) {
			return Pair(ByteArray(0), StError(nowSt.err, true))
		}
		if (nowSt != State.Connected) {
			return Pair(ByteArray(0), StError(Error("not connected"), true))
		}

		val reqId = reqId()
		val (request, err) = FakeHttpRequest(data, headers)
		if (err != null) {
			logger.Debug("Net[$flag]<$connectID>.send:FakeHttpRequest"
				, """$headers (reqId:${reqId}) --- error: $err""")
			return Pair(ByteArray(0), StError(err, false))
		}
		request.setReqId(reqId)
		if (request.encodedData.size > handshake.MaxBytes) {
			logger.Debug("Net[$flag]<$connectID>.send:MaxBytes"
				, """$headers (reqId:${reqId}) --- error: Too Large""")
			return Pair(ByteArray(0)
				, StError(Error("""request.size(${request.encodedData.size}) > MaxBytes(${handshake.MaxBytes})""")
					, false))
		}

		// 在客户端超时也认为是一个请求结束，但是真正的请求并没有结束，所以在服务器看来，仍然占用服务器的一个并发数
		// 因为网络异步的原因，客户端并发数不可能与服务器完全一样，所以这里主要是协助服务器做预控流，按照客户端的逻辑处理即可

		try {
			val ch = allRequests.Add(reqId)

			val ret = withTimeoutOrNull(timeout) {
				val sendJob = launch(Dispatchers.IO) {
					sendSafely(request.encodedData)?.let {
						ch.send(Pair(FakeHttpResponse(), it))
					}
				}

				val ret = ch.receive()
				// 再强制结束 send, 以免阻塞
				sendJob.cancel()

				return@withTimeoutOrNull ret
			}?.let {
				if (it.second != null) {
					return@let Pair(ByteArray(0), it.second)
				}

				logger.Debug("Net[$flag]<$connectID>.send:response"
					, """$headers (reqId:${reqId}) --- ${it.first.status}""")

				return@let if (it.first.status != FakeHttpResponse.Status.Ok)
					Pair(ByteArray(0), StError(Error(String(it.first.data)), false))
				else Pair(it.first.data, null)
			}

			// ret == null: timeout
			logger.Debug("Net[$flag]<$connectID>.send:Timeout"
				, """$headers (reqId:${reqId}) --- timeout(>${timeout.inWholeSeconds}$)""")
			return ret?:Pair(ByteArray(0)
				, TimeoutStError(Error("""request timeout(${timeout.inWholeSeconds}s)"""), false))

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
				sendSafely(pushAck.first)?.let {
					logger.Debug("Net[$flag]<$connectID>.onMessage:pushAck"
						, """error --- ${it.message}:unknown""")
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
		try {
			connLocker.writeLock().lock()
			val old = this.state

			if (this.state is State.Invalidated) {
				return old
			}

			this.state = State.Invalidated(error)
			logger.Debug("Net[$flag]<$connectID>.Invalidated", error.message?:"unknown")

			// 所有连接
			for (ch in waitingConnects) {
				ch.send(error)
				ch.close()
			}
			waitingConnects.clear()

			// 所有请求
			allRequests.ClearAllWith(Pair(FakeHttpResponse(), StError(error, true)))

			return old

		} finally {
			connLocker.writeLock().unlock()
		}
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

