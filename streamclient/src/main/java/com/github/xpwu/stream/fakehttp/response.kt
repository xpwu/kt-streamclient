package com.github.xpwu.stream.fakehttp

import com.github.xpwu.stream.fakehttp.Response.Status
import java.util.Arrays

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

internal class Response internal constructor(internal var reqID: Long = 0L
												 , internal var status: Status = Status.Failed
												 , internal var data: ByteArray = ByteArray(0)) {

	internal var pushID: ByteArray = ByteArray(0)

	internal enum class Status {
		Ok,
		Failed
	}

	companion object {
		// reqid + status + pushid
		internal val MaxNoLoadLen = 4 + 1 + 4
	}

	internal val isPush: Boolean
		get() = reqID == 1L

	fun newPushAck(): Pair<ByteArray, Error?> {
		if (!isPush || pushID.size != 4) {
			return Pair(ByteArray(0), Error("invalid push data"))
		}

		val data = ByteArray(4 + 1 + 4)
		data[0] = ((reqID and 0xff000000L) shr 24).toByte()
		data[1] = ((reqID and 0xff0000L) shr 16).toByte()
		data[2] = ((reqID and 0xff00L) shr 8).toByte()
		data[3] = (reqID and 0xffL).toByte()

		data[4] = 0

		data[5] = pushID[0]
		data[6] = pushID[1]
		data[7] = pushID[2]
		data[8] = pushID[3]

		return Pair(data, null)
	}

}

internal fun ByteArray.parse(): Pair<Response, Error?> {
	val res = Response()

	if (this.size < 5) {
		return Pair(res, Error("fakehttp protocol err(response.size < 5)."))
	}

	res.status = if (this[4].toInt() == 0) Status.Ok else Status.Failed

	var reqID: Long = 0
	for (i in 0..3) {
		reqID = (reqID shl 8) + (this[i].toInt() and 0xff)
	}
	res.reqID = reqID

	var offset = 5
	if (reqID == 1L) {
		if (this.size < offset + 4) {
			return Pair(res, Error("fakehttp protocol err(response.size of push < 9)."))
		}
		res.pushID = Arrays.copyOfRange(this, offset, offset + 4)
		offset += 4
	}

	if (this.size <= offset) {
		res.data = ByteArray(0)
	} else {
		res.data = Arrays.copyOfRange(this, offset, this.size)
	}

	return Pair(res, null)
}

