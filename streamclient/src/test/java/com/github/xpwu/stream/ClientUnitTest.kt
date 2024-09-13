package com.github.xpwu.stream

import com.github.xpwu.stream.lencontent.Host
import com.github.xpwu.stream.lencontent.Option
import com.github.xpwu.stream.lencontent.Port
import com.github.xpwu.x.PrintlnLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileInputStream
import java.util.Properties

class ClientUnitTest {
	private val properties = Properties()
	// 在 project root 目录的 local.properties 文件中，设定 test.host 与 test.port
	private val propertiesFile = FileInputStream("../local.properties")
	private var host = ""
	private var port = 80

	init {
		// 加载文件内容到Properties对象
		properties.load(propertiesFile)
		// 关闭文件输入流
		propertiesFile.close()

		host = properties.getProperty("test.host", "127.0.0.1")
		port = properties.getProperty("test.port", "80").toInt()
	}

	private fun Client(vararg options: Option): Client {
		return Client(*options, logger = PrintlnLogger())
	}

	@Test
	fun properties() {
		val currentPath = System.getProperty("user.dir")
		println("current path: $currentPath")
		println("all properties: $properties")
	}

	@Test
	fun new() {
		Client(Host(host), Port(port))
	}

	@Test
	fun close() {
		val client = Client(Host(host), Port(port))
		client.Close()
	}

	@Test
	fun sendErr(): Unit = runBlocking {
		val client = Client(Host("192.168.0.1"), Port(10000))
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
		val client = Client(Host("192.168.0.1"), Port(10000))
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
		val client = Client(Host("192.168.0.1"), Port(10000))
		assertEquals(true, client.Recover()?.isConnError)
	}

	@Test
	fun recover(): Unit = runBlocking {
		val client = Client(Host(host), Port(port))
		assertNull(client.Recover())
	}
}

