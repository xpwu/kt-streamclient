package com.github.xpwu.stream.websocket

import com.github.xpwu.stream.Client
import com.github.xpwu.stream.LocalProperties
import com.github.xpwu.stream.SendWithReqId
import com.github.xpwu.stream.withWebsocket
import com.github.xpwu.x.PrintlnLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@Serializable
data class ReturnRequest(@SerialName("data") val Data: String)

@Serializable
data class ReturnResponse(@SerialName("ret") val Ret: String)

@Serializable
data class PushReq(@SerialName("times") val times: Int
							, @SerialName("prefix") val prefix: String) {

	@Transient
	var results: MutableSet<String> = HashSet()

	init {
		for (i in 0 ..< times) {
			results.add("$prefix-$i")
		}
	}
}

// ${prefix}-${no.}
private fun PushReq.check(str: String): Boolean {
	return results.remove(str)
}

inline fun <reified T>Json.encodeToByteArray(value: T): ByteArray {
	return Json.encodeToString(serializersModule.serializer(), value).toByteArray()
}

inline fun <reified T>Json.decodeFromByteArray(value: ByteArray): T {
	return Json.decodeFromString<T>(value.toString(Charsets.UTF_8))
}

class SendUnitTest {
	private val properties = LocalProperties()

	private fun Client(): Client {
		return Client.withWebsocket(Url(properties.Url()), logger = PrintlnLogger())
	}

	@Test
	fun sendOne(): Unit = runBlocking{
		val client = Client()
		val req = ReturnRequest("ksjhacslfcksls")
		val ret = client.SendWithReqId(Json.encodeToByteArray(req), mapOf("api" to "return"))
		assertNull("${ret.second}", ret.second)
		val res = Json.decodeFromByteArray<ReturnResponse>(ret.first)
		client.Close()
		assertEquals(req.Data, res.Ret)
	}

	@Test
	fun sendMore(): Unit = runBlocking{
		val client = Client()
		listOf("woenkx",
			"0000今天很好",
			"kajiwnckajie",
			"val req = ReturnRequest(it)",
			"xpwu.kt-streamclient"
			).map {
			launch {
				val req = ReturnRequest(it)
				val ret = client.SendWithReqId(Json.encodeToByteArray(req), mapOf("api" to "return"))
				assertNull("${ret.second}", ret.second)
				val res = Json.decodeFromByteArray<ReturnResponse>(ret.first)
				assertEquals(req.Data, res.Ret)
			}
		}
	}

	@Test
	fun sendClose(): Unit = runBlocking {
		val client = Client()
		val ch = Channel<Boolean>()
		client.onPeerClosed = {
			ch.send(true)
		}

		val ret = client.SendWithReqId("{}".toByteArray(), mapOf("api" to "close"))
		assertNull("${ret.second}", ret.second)
		assertEquals("{}", ret.first.toString(Charsets.UTF_8))

		val rt = withTimeoutOrNull(5.seconds) {
			ch.receive()
		}
		assertNotNull("timeout(5s): not receive onPeerClosed", rt)
		assertTrue(rt!!)
	}

	@Test
	fun sendPush(): Unit = runBlocking {
		val req = PushReq(3, "this is a push test")
		val client = Client()
		val ch = Channel<Boolean>(req.times)
		client.onPush = {
			println("receive push data: ${it.toString(Charsets.UTF_8)}")
			ch.send(req.check(it.toString(Charsets.UTF_8)))
			if (req.results.size == 0) {
				ch.close()
			}
		}

		val ret = client.SendWithReqId(Json.encodeToByteArray(req), mapOf("api" to "PushLt20Times"))
		assertNull("${ret.second}", ret.second)
		assertEquals("{}", ret.first.toString(Charsets.UTF_8))

		val rt = withTimeoutOrNull(30.seconds) {
			for (p in ch) {
				if (!p) {
					return@withTimeoutOrNull false
				}
			}
			return@withTimeoutOrNull true
		}
		assertNotNull("timeout(30s): not receive all onPush", rt)
		assertTrue(rt!!)
	}

}
