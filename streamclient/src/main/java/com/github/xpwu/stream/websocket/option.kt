package com.github.xpwu.stream.websocket

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class OptionValue {
	var url: String = "ws://127.0.0.1:10000"
	var connectTimeout: Duration = 30.seconds

	override fun toString(): String {
		return "${url}#connectTimeout=${connectTimeout}"
	}
}

//typealias Option = (OptionValue)->Unit

class Option internal constructor(internal val runner: (OptionValue)->Unit)

// url prefix with: `ws://` or `wss://`
fun Url(url: String): Option {
	return Option{
		value ->
		if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
			value.url = "ws://$url"
		} else {
			value.url = url
		}
	}
}

fun ConnectTimeout(duration: Duration): Option {
	return Option{
			value -> value.connectTimeout = duration
	}
}

