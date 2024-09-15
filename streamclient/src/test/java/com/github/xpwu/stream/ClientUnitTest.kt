package com.github.xpwu.stream

import com.github.xpwu.stream.lencontent.Host
import com.github.xpwu.stream.lencontent.Port
import com.github.xpwu.x.PrintlnLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientUnitTest {
	private val properties = LocalProperties()

	private fun Client(): Client {
		return Client(Host(properties.Host()), Port(properties.Port()), logger = PrintlnLogger())
	}

	private fun NoConnClient(): Client {
		return Client(Host("10.0.0.0"), Port(0), logger = PrintlnLogger())
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
		assertEquals(true, ret.second?.isConnError)
		ret = client.Send("{}".encodeToByteArray(), headers)
		assertEquals(true, ret.second?.isConnError)
		ret = client.Send("{}".encodeToByteArray(), headers)
		assertEquals(true, ret.second?.isConnError)
	}

	@Test
	fun asyncSendErr(): Unit = runBlocking {
		val client = NoConnClient()
		val headers = HashMap<String, String>()
		headers["api"] = "/mega"

		repeat(10) {
			launch {
				assertEquals(true, client.Send("{}".encodeToByteArray(), headers).second?.isConnError)
			}
		}
	}

	@Test
	fun recoverErr(): Unit = runBlocking {
		val client = NoConnClient()
		assertEquals(true, client.Recover()?.isConnError)
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

