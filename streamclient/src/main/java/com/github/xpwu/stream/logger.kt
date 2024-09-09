package com.github.xpwu.stream

import android.util.Log

interface Logger {
	fun Info(msg: String)
	fun Warning(msg: String)
	fun Error(msg: String)
}

internal class SysLogger: Logger {
	override fun Info(msg: String) {
		Log.i("", msg)
	}

	override fun Warning(msg: String) {
		Log.w("", msg)
	}

	override fun Error(msg: String) {
		Log.e("", msg)
	}

}
