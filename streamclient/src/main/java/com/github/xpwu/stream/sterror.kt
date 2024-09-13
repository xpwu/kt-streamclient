package com.github.xpwu.stream

class StError(internal val err: Error, internal val isConnError: Boolean)
	: Throwable("""${err.message}, isConnError: $isConnError""", err)

val StError.RawError
	get() = err

val StError.IsConnError
	get() = isConnError
