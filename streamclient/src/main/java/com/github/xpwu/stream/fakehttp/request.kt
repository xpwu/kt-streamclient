package com.github.xpwu.stream.fakehttp

import android.util.Log
import android.util.Pair
import java.nio.charset.StandardCharsets

/**
 *
 *	fakehttp protocol:
 *		request ---
 *			reqid | headers | header-end-flag | data
 *				reqid: 4 bytes, net order;
 *				headers: < key-len | key | value-len | value > ... ;  [optional]
 *					key-len: 1 byte,  key-len = sizeof(key);
 *					value-len: 1 byte, value-len = sizeof(value);
 *				header-end-flag: 1 byte, === 0;
 *				data:       [optional]
 *
 *			reqid = 1: client push ack to server.
 *				ack: no headers;
 *				data: pushId. 4 bytes, net order;
 *
 *	---------------------------------------------------------------------
 *		response ---
 *			reqid | status | data
 *				reqid: 4 bytes, net order;
 *				status: 1 byte, 0---success, 1---failed
 *				data: if status==success, data=<app data>    [optional]
 *							if status==failed, data=<error reason>
 *
 *
 *			reqid = 1: server push to client
 *				status: 0
 *				data: first 4 bytes --- pushId, net order;
 *							last --- real data
 *
 */


internal class Request(body: ByteArray, headers: Map<String, String>) {

	private val data: ByteArray

	internal val encodedData: ByteArray get() = data

	init {
		var length = 4 + 1
		length += body.size

		val headerList = ArrayList<Pair<ByteArray, ByteArray>>()
		for ((key1, value1) in headers) {
			val key = key1.toByteArray(StandardCharsets.UTF_8)
			val value = value1.toByteArray(StandardCharsets.UTF_8)

			if (key.size > 255 || value.size > 255) {
				val e = Exception(
					"key('" + key1 + "')'s length or value('"
						+ value1 + "') is more than 255 "
				)
				Log.e("fakeHttp.request", "header error", e)
				throw e
			}
			length += 1 + key.size + 1 + value.size

			headerList.add(Pair(key, value))
		}

		val request = ByteArray(length)


		var pos = 4

		for (entry in headerList) {
			val key = entry.first
			val value = entry.second

			request[pos] = key.size.toByte()
			pos++
			System.arraycopy(key, 0, request, pos, key.size)
			pos += key.size
			request[pos] = value.size.toByte()
			pos++
			System.arraycopy(value, 0, request, pos, value.size)
			pos += value.size
		}

		request[pos] = 0 // header-end
		pos++

		System.arraycopy(body, 0, request, pos, body.size)

		data = request
	}

	fun setReqId(reqId: Long) {
		data[0] = ((reqId and 0xff000000L) shr 24).toByte()
		data[1] = ((reqId and 0xff0000L) shr 16).toByte()
		data[2] = ((reqId and 0xff00L) shr 8).toByte()
		data[3] = (reqId and 0xffL).toByte()
	}

}


