@file:JvmName("Option_Kt")

package com.github.xpwu.stream

import java.net.Socket

class OptionKt (internal val o : OptionJava)

fun Host(host: String): OptionKt {
	return OptionKt(OptionJava.Host(host))
}

fun Port(port: Int): OptionKt {
	return OptionKt(OptionJava.Port(port))
}

fun TLS(): OptionKt {
	return OptionKt(OptionJava.TLS())
}

fun TLS(strategy: (host: String, port: Int , tcpSocket: Socket)->Socket): OptionKt {
	return OptionKt(OptionJava.TLS(strategy))
}

fun ConnectTimeout(duration: Duration): OptionKt {
	return OptionKt(OptionJava.ConnectTimeout(
		DurationJava(
			duration.d
		)
	))
}

fun RequestTimeout(duration: Duration): OptionKt {
	return OptionKt(OptionJava.RequestTimeout(
		DurationJava(
			duration.d
		)
	))
}

internal fun Array<out OptionKt>.toOptions(): Array<OptionJava> {
	val l = this.map { return@map it.o }
	return l.toTypedArray()
}