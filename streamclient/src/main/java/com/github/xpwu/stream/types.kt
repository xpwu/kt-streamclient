package com.github.xpwu.stream

class StError(internal val err: Error, internal val isConnError: Boolean)

val StError.RawError
	get() = err

val StError.IsConnError
	get() = isConnError



//typealias ByteArray = ByteArray
//
//typealias Pusher = (ByteArray) -> Unit

//typealias PeerClosed = () -> Unit




