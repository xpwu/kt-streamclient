package com.github.xpwu.stream

open class StError(internal val err: Error, internal val isConnError: Boolean)
	: Throwable("""${err.message}, isConnError: $isConnError""", err)

val StError.RawError
	get() = err

val StError.IsConnError
	get() = isConnError

class TimeoutStError(err: Error, isConnError: Boolean): StError(err, isConnError)

class TimeoutError(msg: String): Error(msg)

