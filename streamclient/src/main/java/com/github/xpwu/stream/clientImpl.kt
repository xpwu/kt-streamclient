package com.github.xpwu.stream

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Semaphore

import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.github.xpwu.stream.fakehttp.Response as FakeHttpResponse
import com.github.xpwu.stream.fakehttp.Request as FakeHttpRequest

private const val reqIdStart: Long = 10

internal class ClientImpl(private val net:net): net.Delegate {
	init {
		this.net.setDelegate(this)
	}

	internal var push: Pusher = {}
	internal var peerClosed: PeerClosed = {}

	internal enum class ConnectState {
		NotConnect, Connecting, Connected, Closing
	}
	private val connLocker: ReadWriteLock = ReentrantReadWriteLock()
	private var connState = ConnectState.NotConnect
	private val waitingConnects: MutableList<SendChannel<StError?>> = ArrayList()

	private var reqId = reqIdStart

	private var reqSemaphore: Semaphore = Semaphore(5)
	private val allRequests: MutableMap<Long, SendChannel<FakeHttpResponse>> = HashMap()

	private var handshake: net.Handshake = net.Handshake()

	//////------

//	private fun asyncErr(handler: ClientJava.ErrorHandler, error: Error, isConn: Boolean) {
//		Handler().post { handler.onFailed(error, isConn) }
//	}
//
//	private fun asyncConnectSuc(handler: ConnectHandler) {
//		Handler().post { handler.onSuccess() }
//	}
//
//	private fun asyncSendSuc(handler: ClientJava.ResponseHandler, response: ByteArray) {
//		Handler().post { handler.onSuccess(response) }
//	}
//
//	private suspend fun runWaiting(error: Error?) {
//		for (ch in waitingConnects) {
//			ch.send(error?.let { StError(it, true) } )
//			ch.close()
//		}
//
//		waitingConnects.clear()
//	}

	private suspend fun doAllRequest(response: FakeHttpResponse) {
		for ((_, ch) in allRequests) {
			ch.send(response)
		}
		allRequests.clear()
	}

	@Synchronized
	private fun reqId(): Long {
		reqId++
		if (reqId < reqIdStart || reqId > Int.MAX_VALUE) {
			reqId = reqIdStart
		}
		return reqId
	}

	// 注意：connect onConnected close onClose 之间的时序问题
	// 如果执行了connect，再执行close，然后再被调用onConnected,
	// 返给上层的信息也不能是连接成功的信息；
	// 如果执行了close，再执行connect成功了，最后给用户的也只能是连接成功
	// 无论当前连接状态，都可以重复调用。

	// 不能确定 Net 的实现每次connect都回调 onConnected，
	// 所以上层通过ConnectState的判断作了逻辑加强，无论 Net 是否调用都需要
	// 确保逻辑正确
	suspend fun connect(): StError? {
		connLocker.readLock().lock()
		val nowSt = this.connState
		connLocker.readLock().unlock()
		if (nowSt == ConnectState.Connected) {
			return null
		}

		connLocker.writeLock().lock()
		if (this.connState == ConnectState.Connected) {
			connLocker.writeLock().unlock()
			return null
		}

		// 一次真实连接中的所有连接请求“一视同仁”
		if (this.connState == ConnectState.Connecting) {
			val ch = Channel<StError?>(1)
			waitingConnects.add(ch)
			connLocker.writeLock().unlock()
			return ch.receive()
		}

		this.connState = ConnectState.Connecting
		connLocker.writeLock().unlock()

		val connRet = this.net.connect()
		this.handshake = connRet.first
		this.reqSemaphore = Semaphore(this.handshake.MaxConcurrent)

		val ret = connRet.second?.let { StError(it, true) }

		connLocker.writeLock().lock()
		this.connState = if (connRet.second == null) ConnectState.Connected else ConnectState.NotConnect
		for (ch in waitingConnects) {
			ch.send(ret)
			ch.close()
		}
		waitingConnects.clear()
		connLocker.writeLock().unlock()

		return ret
	}

	// 无论当前连接状态，都可以重复调用
	suspend fun close() {
		if (connState == ConnectState.NotConnect) {
			return
		}

		runWaiting(Error("connection closed by self"))
		connState = ConnectState.Closing
		// todo: net.close() 结束后，是否会调用 net.delegate.onClose() ？
		net.close()
	}

	// 如果没有连接成功，直接返回失败
	suspend fun send(data: ByteArray, headers: Map<String, String>
									 , timeout: Duration = 30.seconds): Pair<ByteArray, StError?> {
		connLocker.readLock().lock()
		val nowSt = this.connState
		connLocker.readLock().unlock()
		if (nowSt != ConnectState.Connected) {
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

		val ch = Channel<FakeHttpResponse>(1)
		synchronized(allRequests){
			allRequests[reqId] = ch
		}

		this.net.send(request.encodedData)

		var ret = withTimeoutOrNull(timeout) {
			return@withTimeoutOrNull ch.receive()
		}?.let {
			return@let if (it.status != FakeHttpResponse.Status.Ok)
				Pair(ByteArray(0), StError(Error(String(it.data)), false))
			 else Pair(it.data, null)
		}
		// timeout
		if (ret == null) {
			synchronized(allRequests){
				allRequests.remove(reqId)
			}
			ret = Pair(ByteArray(0)
				, StError(Error("""request timeout(${timeout.inWholeSeconds}s)"""), false))
		}

		reqSemaphore.release()

		return ret
	}

//	fun updateNetConnectTime() {
//		netJava!!.setConfig(
//			NetJava.Config(
//				config!!.connectTimeout,
//				config!!.heartbeatTime,
//				config!!.frameTimeout
//			)
//		)
//	}

//	override suspend fun onConnected(handshake: Net.Handshake) {
//		this.handshake = handshake
//		this.runWaiting(null)
//	}

	override fun onMessage(message: ByteArray) {
		TODO("Not yet implemented")
	}

	override fun onClosed(reason: String) {
		TODO("Not yet implemented")
	}

	override fun onError(error: Error) {
		TODO("Not yet implemented")
	}

	fun setNet(netJava: NetJava) {
		this.netJava = netJava
		this.netJava!!.setConfig(
			NetJava.Config(
				config!!.connectTimeout,
				config!!.heartbeatTime,
				config!!.frameTimeout
			)
		)
		this.netJava!!.setDelegate(object : NetJava.Delegate {
			override fun onConnected() {
				if (connState != ConnectState.Connecting) {
					return
				}
				connState = ConnectState.Connected
				runWaiting(null)
			}

			override fun onMessage(message: ByteArray) {
				var response: FakeHttpJava.Response? = null
				try {
					response = FakeHttpJava.Response(message)
				} catch (e: Exception) {
					var str = e.message
					if (str == null) {
						str = "fakeHttp response error"
					}
					response = FakeHttpJava.Response.fromError(reqId, str)
				}

				if (response!!.isPush) {
					// push ack 强制写给网络，不计入并发控制
					netJava.sendForce(response.newPushAck())
					pushCallback!!.onPush(response.data)
					return
				}

				netJava.receivedOneResponse()

				// 这里直接调用allRequests.remove(response.reqID)代替get函数更合适，确保已删除
				val r = allRequests.remove(
					response.reqID
				)
				if (r == null) {
					// 这里由error修改为warning，不存在ResponseHandler也可能是一种正常的情况，比如：超时后才收到服务器的response
					Log.w("client", "onMessage: not find response handler for " + response.reqID)
					return
				}
				r.onResponse(response)
			}

			override fun onClosed(reason: String) {
				if (connState == ConnectState.NotConnect) {
					return
				}
				Log.i("stream.ClientImpl", "onClosed: $reason")

				// 上一次连接后，所有的请求都需要回应
				doAllRequest(FakeHttpJava.Response.fromError(0, reason))

				// 正在连接中，不再做关闭的状态处理 (可能是调用了close(), 马上就调用了 connect() 的情况)
				if (connState == ConnectState.Connecting) {
					return
				}

				if (connState == ConnectState.Connected) {
					peerClosedCallback!!.onPeerClosed()
				}
				connState = ConnectState.NotConnect
			}

			override fun onError(error: Error) {
				// 关闭中的错误，暂都默认不处理。
				if (connState == ConnectState.Closing || connState == ConnectState.NotConnect) {
//          Log.d("ClientImpl", "onError: ", error);
					return
				}

				Log.e("stream.ClientImpl", "onError: ", error)

				// 不管什么错误，都需要清除等待中的连接
				runWaiting(error)

				// 不确定onError的时候是否已经自动会执行onClosed，这里再次明确执行一次，
				// 但是要注意onClosed的逻辑多次执行也要没有问题
				this.onClosed(error.message!!)

				// 发生了错误，就要执行一次关闭的操作
				// 前面的其他操作可能 更改了connState，这里做二次确认
				if (connState == ConnectState.Connecting || connState == ConnectState.Connected) {
					connState = ConnectState.Closing
				}
				netJava.close()
			}
		})
	}

}

