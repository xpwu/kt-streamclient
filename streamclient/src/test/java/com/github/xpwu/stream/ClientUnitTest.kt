package com.github.xpwu.stream

import com.github.xpwu.stream.lencontent.Host
import com.github.xpwu.stream.lencontent.Port
import com.github.xpwu.x.PrintlnLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientUnitTest {
	private val properties = LocalProperties()

	private var host = properties.Host()
	private var port = properties.Port()

	private fun Client(): Client {
		return Client(Host(host), Port(port), logger = PrintlnLogger())
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

		val all = listOf(0, 1, 2, 3, 4)
		val deferreds = all.map {
			async {
				client.Send("{}".encodeToByteArray(), headers)
			}
		}
		deferreds.forEach{
			assertEquals(true, it.await().second?.isConnError)
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
}

