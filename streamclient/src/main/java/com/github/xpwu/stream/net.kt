package com.github.xpwu.stream

import com.github.xpwu.stream.fakehttp.parse
import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.github.xpwu.stream.fakehttp.Response as FakeHttpResponse
import com.github.xpwu.stream.fakehttp.Request as FakeHttpRequest

private const val reqIdStart: Long = 10

// onPeerClosed: Net.close() 的调用不会触发 onPeerClosed
internal class Net internal constructor(protocolCreator: ()->Protocol
																				, private val onPeerClosed: suspend (Error)->Unit
																				, private val onPush: suspend (ByteArray)->Unit): Protocol.Delegate {

	internal val isInValid
		get() = state == State.Invalidated

	internal var logger: Logger = AndroidLogger()

	private var handshake: Protocol.Handshake = Protocol.Handshake()

	private val protocol: Protocol = protocolCreator()
	init {
		protocol.setDelegate(this)
		protocol.setLogger(logger)
	}

	private enum class State {
		NotConnect, Connecting, Connected, Invalidated
	}
	private val connLocker: ReadWriteLock = ReentrantReadWriteLock()
	private var state = State.NotConnect
	private val waitingConnects: MutableList<SendChannel<Error?>> = ArrayList()
	private var lastErr: Error? = null

	private var reqId = reqIdStart

	private var reqSemaphore: Semaphore = Semaphore(5)

	private val reqMutex: Mutex = Mutex()
	private val allRequests: MutableMap<Long, SendChannel<Pair<FakeHttpResponse, StError?>>> = HashMap()

	protected fun finalize() {
		protocol.close()
	}

	// 可重复调用
	internal suspend fun connect(): Error? {
		connLocker.readLock().lock()
		val nowSt = this.state
		connLocker.readLock().unlock()
		if (nowSt == State.Connected) {
			return null
		}
		if (nowSt == State.Invalidated) {
			return this.lastErr
		}

		connLocker.writeLock().lock()
		if (this.state == State.Connected) {
			connLocker.writeLock().unlock()
			return null
		}
		if (this.state == State.Invalidated) {
			connLocker.writeLock().unlock()
			return this.lastErr
		}

		// 所有连接请求“一视同仁”
		if (this.state == State.Connecting) {
			val ch = Channel<Error?>(1)
			waitingConnects.add(ch)
			connLocker.writeLock().unlock()
			logger.Debug("Net.connect --- state==Connecting", "wait for connecting")
			return ch.receive()
		}

		this.state = State.Connecting
		connLocker.writeLock().unlock()

		logger.Debug("Net.connect --- state!=Connecting", "will connect")
		val connRet = this.protocol.connect()
		this.handshake = connRet.first
		this.reqSemaphore = Semaphore(this.handshake.MaxConcurrent)
		logger.Info("Net.connect --- handshake" , this.handshake.Info())

		connLocker.writeLock().lock()
		this.state = if (connRet.second == null) State.Connected else State.Invalidated
		this.lastErr = connRet.second
		for (ch in waitingConnects) {
			ch.send(connRet.second)
			ch.close()
		}
		waitingConnects.clear()
		connLocker.writeLock().unlock()

		return connRet.second
	}

	internal fun close() {
		connLocker.writeLock().lock()

		if (this.state == State.Invalidated) {
			connLocker.writeLock().unlock()
			return
		}

		this.state = State.Invalidated
		this.lastErr = Error("closed by self")
		logger.Info("Net.close", "closed, become invalidated")

		connLocker.writeLock().unlock()

		this.protocol.close()
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
		connLocker.readLock().lock()
		val nowSt = this.state
		connLocker.readLock().unlock()
		if (nowSt == State.Invalidated) {
			return Pair(ByteArray(0), StError(lastErr?:UnknownError(), true))
		}
		if (nowSt != State.Connected) {
			return Pair(ByteArray(0), StError(Error("not connected"), true))
		}

		val reqId = reqId()
		val request = FakeHttpRequest(data, headers)
		request.setReqId(reqId)
		if (request.encodedData.size > handshake.MaxBytes) {
			return Pair(ByteArray(0)
				, StError(Error("""request.size(${request.encodedData.size}) > MaxBytes(${handshake.MaxBytes})""")
					, false))
		}

		// 在客户端超时也认为是一个请求结束，但是真正的请求并没有结束，所以在服务器看来，仍然占用服务器的一个并发数
		// 因为网络异步的原因，客户端并发数不可能与服务器完全一样，所以这里主要是协助服务器做预控流，按照客户端的逻辑处理即可
		reqSemaphore.acquire()

		val ch = Channel<Pair<FakeHttpResponse, StError?>>(1)

		reqMutex.lock()
		// 需要在获取锁后，再次判断是否失效
		// 可能 onError 刚好在这之前被触发了，allRequests 已经全部触发了
		// 如果此时再把 ch 直接放入 allRequests 中，就不会收到 channel 信号了，
		//    只能等待后续的超时，这样造成没必要的时间等待
		if (state == State.Invalidated) {
			reqMutex.unlock()
			return Pair(ByteArray(0), StError(lastErr?:UnknownError(), true))
		}
		allRequests[reqId] = ch
		reqMutex.unlock()

		this.protocol.send(request.encodedData)?.let {
			reqMutex.lock()
			allRequests.remove(reqId)
			reqMutex.unlock()

			return Pair(ByteArray(0), StError(it, false))
		}

		var ret = withTimeoutOrNull(timeout) {
			return@withTimeoutOrNull ch.receive()
		}?.let {
			if (it.second != null) {
				return@let Pair(ByteArray(0), it.second)
			}

			return@let if (it.first.status != FakeHttpResponse.Status.Ok)
				Pair(ByteArray(0), StError(Error(String(it.first.data)), false))
			else Pair(it.first.data, null)
		}

		// timeout
		if (ret == null) {
			reqMutex.lock()
			allRequests.remove(reqId)
			reqMutex.unlock()

			ret = Pair(ByteArray(0)
				, StError(Error("""request timeout(${timeout.inWholeSeconds}s)"""), false))
		}

		reqSemaphore.release()

		return ret
	}

	override suspend fun onMessage(message: ByteArray) {
		val res = message.parse()
		res.second?.let {
			onError(it)
			return
		}

		val response = res.first

		if (response.isPush) {
			val pushAck = response.newPushAck()
			pushAck.second?.let {
				onError(it)
				return
			}

			withContext(Dispatchers.Default) {
				launch {
					// ignore error
					this@Net.protocol.send(pushAck.first)
					this@Net.onPush(response.data)
				}
			}
			return
		}

		reqMutex.lock()
		// 这里直接调用allRequests.remove(response.reqID)代替get函数更合适，确保已删除
		val ch = allRequests.remove(response.reqID)
		reqMutex.unlock()
		ch?.send(Pair(response, null))
	}

	override suspend fun onError(error: Error) {
		logger.Error("Net.onError", error.toString())

		connLocker.writeLock().lock()

		if (this.state == State.Invalidated) {
			connLocker.writeLock().unlock()
			return
		}

		this.state = State.Invalidated
		this.lastErr = error

		withContext(Dispatchers.Default) {
			launch {
				onPeerClosed(error)
			}
		}

		// 所有连接
		for (ch in waitingConnects) {
			ch.send(error)
			ch.close()
		}
		waitingConnects.clear()

		connLocker.writeLock().unlock()

		// 所有请求
		reqMutex.lock()
		for ((_, ch) in allRequests) {
			ch.send(Pair(FakeHttpResponse(), StError(error, true)))
			ch.close()
		}
		allRequests.clear()
		reqMutex.unlock()

		this.protocol.close()
	}

}

