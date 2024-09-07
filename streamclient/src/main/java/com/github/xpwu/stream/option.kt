package com.github.xpwu.stream

import java.net.Socket
import kotlin.time.Duration

class Option {
	internal var o: OptionJava? = null

	internal fun set(o: OptionJava): Option {
		this.o = o
		return this
	}
}

fun Host(host: String): Option {
	return Option().set(OptionJava.Host(host))
}

fun Port(port: Int): Option {
	return Option().set(OptionJava.Port(port))
}

fun TLS(): Option {
	return Option().set(OptionJava.TLS())
}

fun TLS(strategy: (host: String, port: Int , tcpSocket: Socket)->Socket): Option {
	return Option().set(OptionJava.TLS(strategy))
}

fun ConnectTimeout(duration: Duration): Option {
	return Option().set(OptionJava.ConnectTimeout(
		DurationJava(
			duration.inWholeMilliseconds * DurationJava.Millisecond
		)
	))
}

fun RequestTimeout(duration: Duration): Option {
	return Option().set(OptionJava.RequestTimeout(
		DurationJava(
			duration.inWholeMilliseconds * DurationJava.Millisecond
		)
	))
}

internal fun Array<out Option>.toOptions(): Array<OptionJava> {
	val list = ArrayList<OptionJava>(this.size)
	for (opt in this) {
		opt.o?. let {
			list.add(it)
		}
	}

	val ret = Array<OptionJava>(0){ return@Array OptionJava.Host("")}
	return list.toArray(ret)
}
