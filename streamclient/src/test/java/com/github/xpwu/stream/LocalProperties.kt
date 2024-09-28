package com.github.xpwu.stream

import java.io.FileInputStream
import java.util.Properties

class LocalProperties {
	private val properties = Properties()
	// 在 project root 目录的 local.properties 文件中，设定各个键值对
	private val propertiesFile = FileInputStream("../local.properties")

	init {
		// 加载文件内容到Properties对象
		properties.load(propertiesFile)
		// 关闭文件输入流
		propertiesFile.close()
	}

	override fun toString(): String {
		return properties.toString()
	}

	fun Host(): String {
		return properties.getProperty("test.host", "127.0.0.1")
	}

	fun Port(): Int {
		return properties.getProperty("test.port", "8080").toInt()
	}

	fun Url(): String {
		return properties.getProperty("test.url", "127.0.0.1:8001")
	}
}