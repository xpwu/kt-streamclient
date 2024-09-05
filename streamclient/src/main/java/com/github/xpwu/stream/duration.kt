package com.github.xpwu.stream

class Duration(internal val d: Long) {
	companion object {
		const val MicroSecond: Long = 1L
		const val MilliSecond = 1000 * MicroSecond
		const val Second = 1000 * MilliSecond
		const val Minute = 60 * Second
		const val Hour = 60 * Minute
	}
}

val Duration.Second
	get() = d/Duration.Second

val Duration.MilliSecond
	get() = d/Duration.MilliSecond

val Duration.Minute
	get() = d/Duration.Minute
