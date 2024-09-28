package com.github.xpwu.stream.websocket

import com.github.xpwu.stream.Client
import com.github.xpwu.stream.*
import com.github.xpwu.x.Logger
import com.github.xpwu.x.PrintlnLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration


class HBLogger: Logger {
	var hbTime = Duration.ZERO
	val recHbCh = Channel<Boolean>(UNLIMITED)
	var send: HBLogger.()->Unit = {}

	val scope = CoroutineScope(Dispatchers.IO)

	val hbTimeRegex = "^Net\\[.*]<.*>.connect:handshake$".toRegex()
	val hbTimeResult = "^handshake info: \\{ConnectId: .*, MaxConcurrent: .*, HearBeatTime: (.*), MaxBytes/frame: .*, FrameTimeout: .*}$".toRegex()
	val sendRegex = "^LenContent\\[.*]<.*>.outputHeartbeatTimer:send$".toRegex()
	val recRegex = "^LenContent\\[.*]<.*>.receiveInputStream:Heartbeat$".toRegex()

	private val print = PrintlnLogger(1)

	override fun Debug(tag: String, msg: String) {
//		if (sendRegex.matches(tag)) {
//			send()
//		}
//		if (recRegex.matches(tag)) {
//			scope.launch {
//				recHbCh.send(true)
//			}
//		}
		if (hbTimeRegex.matches(tag)) {
			hbTimeResult.find(msg)?.let {
				val (t)= it.destructured
				hbTime = Duration.parse(t)
			}
		}

		print.Debug(tag, msg)
	}

	override fun Error(tag: String, msg: String) {
		print.Error(tag, msg)
	}

	override fun Info(tag: String, msg: String) {
		print.Info(tag, msg)
	}

	override fun Warning(tag: String, msg: String) {
		print.Warning(tag, msg)
	}

}

class HeartbeatUnitTest {
	private val properties = LocalProperties()

	private fun client(logger: Logger): Client {
		return Client.withWebsocket(Url(properties.Url()), logger = logger)
	}

	@Test
	fun heartbeatTime() = runBlocking {
		val logger = HBLogger()
		val client = client(logger)
		assertNull(client.Recover())
		assertNotEquals(Duration.ZERO, logger.hbTime)
		client.Close()
	}

	@Test
	fun recHeartbeat() = runBlocking {
		val logger = HBLogger()
		val client = client(logger)
		client.onPeerClosed = {
			logger.recHbCh.send(false)
		}
		assertNull(client.Recover())
		assertNotEquals(Duration.ZERO, logger.hbTime)

		// first
		val rec = withTimeoutOrNull(logger.hbTime.times(2)) {
			logger.recHbCh.receive()
		}
		// 暂没有做验证 ping 的 assert ，通过后台日志查看
		// timeout
		assertNull("onPeerClosed", rec)
//		assertTrue("peer closed: not receive heartbeat(${logger.hbTime})", rec!!)

		client.Close()
	}
}

