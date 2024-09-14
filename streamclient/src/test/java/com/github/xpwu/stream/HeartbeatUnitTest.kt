package com.github.xpwu.stream

import com.github.xpwu.stream.lencontent.Host
import com.github.xpwu.stream.lencontent.Port
import com.github.xpwu.x.Logger
import com.github.xpwu.x.PrintlnLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


class HBLogger: Logger {
	var hbTime = Duration.ZERO
	val recHbCh = Channel<Boolean>(UNLIMITED)
	var send: HBLogger.()->Unit = {}

	val scope = CoroutineScope(Dispatchers.IO)

	val hbTimeRegex = "^LenContent\\[.*]<.*>.readHandshake:handshake$".toRegex()
	val hbTimeResult = "^handshake info: \\{ConnectId: .*, MaxConcurrent: .*, HearBeatTime: (.*), MaxBytes/frame: .*, FrameTimeout: .*}$".toRegex()
	val sendRegex = "^LenContent\\[.*]<.*>.outputHeartbeatTimer:send$".toRegex()
	val recRegex = "^LenContent\\[.*]<.*>.receiveInputStream:Heartbeat$".toRegex()

	private val print = PrintlnLogger(1)

	override fun Debug(tag: String, msg: String) {
		if (sendRegex.matches(tag)) {
			send()
		}
		if (recRegex.matches(tag)) {
			scope.launch {
				recHbCh.send(true)
			}
		}
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
		return Client(Host(properties.Host()), Port(properties.Port()), logger = logger)
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
	fun peerClosed() = runBlocking {
		val logger = HBLogger()
		val ch: ReceiveChannel<Boolean> = Channel()
		logger.send = send@{
			runBlocking {
				// block
				ch.receive()
			}
		}

		val peerCh = Channel<Boolean>()
		val client = client(logger)
		client.onPeerClosed = {
			peerCh.send(true)
		}
		assertNull(client.Recover())
		assertNotEquals(Duration.ZERO, logger.hbTime)

		val rec = withTimeoutOrNull(logger.hbTime.times(2).plus(5.seconds)) {
			peerCh.receive()
		}
		// timeout
		assertNotNull("timeout: not receive onPeerClosed", rec)
		assertTrue("error: not receive onPeerClosed", rec!!)
	}

	@Test
	fun sendHeartbeat() = runBlocking {
		val logger = HBLogger()
		val ch = Channel<Boolean>()
		logger.send = send@{
			this@send.scope.launch {
				ch.send(true)
			}
		}

		val client = client(logger)
		client.onPeerClosed = {
			ch.send(false)
		}
		assertNull(client.Recover())
		assertNotEquals(Duration.ZERO, logger.hbTime)

		// first
		var rec = withTimeoutOrNull(logger.hbTime.plus(5.seconds)) {
			ch.receive()
		}
		// timeout
		assertNotNull("timeout: not send heartbeat(${logger.hbTime})", rec)
		assertTrue("peer closed: not send heartbeat(${logger.hbTime})", rec!!)

		// second
		rec = withTimeoutOrNull(logger.hbTime.plus(5.seconds)) {
			ch.receive()
		}
		// timeout
		assertNotNull("timeout: not send heartbeat(${logger.hbTime})", rec)
		assertTrue("peer closed: not send heartbeat(${logger.hbTime})", rec!!)

		// third
		rec = withTimeoutOrNull(logger.hbTime.plus(5.seconds)) {
			ch.receive()
		}
		// timeout
		assertNotNull("timeout: not send heartbeat(${logger.hbTime})", rec)
		assertTrue("peer closed: not send heartbeat(${logger.hbTime})", rec!!)

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
		var rec = withTimeoutOrNull(logger.hbTime.times(2)) {
			logger.recHbCh.receive()
		}
		// timeout
		assertNotNull("timeout: not receive heartbeat(${logger.hbTime})", rec)
		assertTrue("peer closed: not receive heartbeat(${logger.hbTime})", rec!!)

		// second
		rec = withTimeoutOrNull(logger.hbTime.times(2)) {
			logger.recHbCh.receive()
		}
		// timeout
		assertNotNull("timeout: not receive heartbeat(${logger.hbTime})", rec)
		assertTrue("peer closed: not receive heartbeat(${logger.hbTime})", rec!!)

		// third
		rec = withTimeoutOrNull(logger.hbTime.times(2)) {
			logger.recHbCh.receive()
		}
		// timeout
		assertNotNull("timeout: not receive heartbeat(${logger.hbTime})", rec)
		assertTrue("peer closed: not receive heartbeat(${logger.hbTime})", rec!!)

		client.Close()
	}
}

