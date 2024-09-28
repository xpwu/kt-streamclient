package com.github.xpwu.stream.websocket

import com.github.xpwu.stream.*
import com.github.xpwu.x.PrintlnLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientUnitTest {
	private val properties = LocalProperties()

	private fun Client(): Client {
		return Client.withWebsocket(Url(properties.Url()), logger = PrintlnLogger())
	}

	private fun NoConnClient(): Client {
		return Client.withWebsocket(Url("10.0.0.0:0"), logger = PrintlnLogger())
	}

	@Test
	fun properties() {
		val currentPath = System.getProperty("user.dir")
		println("current path: $currentPath")
		println("all properties: $properties")
	}

	@Test
	fun new() {
		Client()
	}

	@Test
	fun close() {
		val client = Client()
		client.Close()
	}

	@Test
	fun sendErr(): Unit = runBlocking {
		val client = NoConnClient()
		val headers = HashMap<String, String>()
		headers["api"] = "/mega"
		var ret = client.Send("{}".encodeToByteArray(), headers)
		assertEquals(true, ret.second?.IsConnError)
		ret = client.Send("{}".encodeToByteArray(), headers)
		assertEquals(true, ret.second?.IsConnError)
		ret = client.Send("{}".encodeToByteArray(), headers)
		assertEquals(true, ret.second?.IsConnError)
	}

	@Test
	fun asyncSendErr(): Unit = runBlocking {
		val client = NoConnClient()
		val headers = HashMap<String, String>()
		headers["api"] = "/mega"

		repeat(10) {
			launch {
				assertEquals(true, client.Send("{}".encodeToByteArray(), headers).second?.IsConnError)
			}
		}
	}

	@Test
	fun recoverErr(): Unit = runBlocking {
		val client = NoConnClient()
		assertEquals(true, client.Recover()?.IsConnError)
	}

	@Test
	fun recover(): Unit = runBlocking {
		val client = Client()
		assertNull(client.Recover())
	}

	@Test
	fun asyncRecover(): Unit = runBlocking {
		val client = Client()
		repeat(10) {
			launch {
				assertNull(client.Recover())
			}
		}
	}
}

