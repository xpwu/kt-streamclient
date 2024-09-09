package com.github.xpwu.stream.lencontent

import android.util.Log
import com.github.xpwu.stream.DurationJava
import com.github.xpwu.stream.LenContentJava.Connection.HandshakeRes
import com.github.xpwu.stream.Protocol
import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import com.github.xpwu.x.Net2Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Random
import java.util.TimerTask
import kotlin.time.Duration.Companion.seconds

private class DummyDelegate(private val logger: Logger): Protocol.Delegate {
  override suspend fun onMessage(message: ByteArray) {
    logger.Debug("LenContent.DummyDelegate.onMessage", """receive data(${message.size}Bytes)""")
  }

  /**
   * 连接成功后，任何不能继续通信的情况都以 onError 返回
   * connect() 的错误不触发 onError，
   * close() 的调用不触发 onError
   */
  override suspend fun onError(error: Error) {
    logger.Error("LenContent.DummyDelegate.onError", error.toString())
  }

}

/**
 *
 * LenContent protocol:
 *
 * 1, handshake protocol:
 *
 *                        client ------------------ server
 *                        |                          |
 *                        |                          |
 *                        ABCDEF (A^...^F = 0xff) --->  check(A^...^F == 0xff) --- N--> over
 *                        (A is version)
 *                        |                          |
 *                        |                          |Y
 *                        |                          |
 * version 1:   set client heartbeat  <----- HeartBeat_s (2 bytes, net order)
 * version 2:       set config     <-----  HeartBeat_s | FrameTimeout_s | MaxConcurrent | MaxBytes | connect id
 *                                          HeartBeat_s: 2 bytes, net order
 *                                          FrameTimeout_s: 1 byte
 *                                          MaxConcurrent: 1 byte
 *                                          MaxBytes: 4 bytes, net order
 *                                          connect id: 8 bytes, net order
 *                        |                          |
 *                        |                          |
 *                        |                          |
 *                        data      <-------->       data
 *
 *
 * 2, data protocol:
 *    1) length | content
 *    length: 4 bytes, net order; length=sizeof(content)+4; length=0 => heartbeat
 *
 */

class LenContent(vararg options: Option) : Protocol {

  internal val optValue: OptionValue = OptionValue()
  internal var logger: Logger = AndroidLogger()
  internal var delegate: Protocol.Delegate = DummyDelegate(logger)
  internal var socket = Socket()

  init {
    for (op in options) {
      op.runner(optValue)
    }
  }

  override suspend fun connect(): Pair<Protocol.Handshake, Error?> {
    return _connect()
  }

  override fun close() {
    _close()
  }

  override suspend fun send(content: ByteArray) {
    _send(content)
  }

  override fun setDelegate(delegate: Protocol.Delegate) {
    this.delegate = delegate
  }

  override fun setLogger(logger: Logger) {
    this.logger = logger
  }
}

private fun handshakeReq(): ByteArray {
  val handshake = ByteArray(6)
  Random().nextBytes(handshake)

  // version is 2
  handshake[0] = 2
  handshake[5] = 0xff.toByte()
  for (i in 0..4) {
    handshake[5] = (handshake[5].toInt() xor (handshake[i] as Byte).toInt()).toByte()
  }

  return handshake
}

@Throws(IOException::class)
private fun readHandshake(inputStream: InputStream): Pair<Protocol.Handshake, Error?> {
  var pos = 0

  /*
      HeartBeat_s: 2 bytes, net order
      FrameTimeout_s: 1 byte
      MaxConcurrent: 1 byte
      MaxBytes: 4 bytes, net order
      connect id: 8 bytes, net order
      */
  val handshake = ByteArray(2 + 1 + 1 + 4 + 8)
  while (handshake.size - pos != 0) {
    val n = inputStream.read(handshake, pos, handshake.size - pos)
    if (n <= 0) {
      return Pair(Protocol.Handshake(), Error("read handshake error, maybe connection closed by peer or timeout"))
    }

    pos += n
  }

  val ret = Protocol.Handshake()
  ret.HearBeatTime = (((0xff and handshake[0].toInt()) shl 8) + (0xff and handshake[1].toInt())).seconds
  ret.FrameTimeout = handshake[2].toInt().seconds // DurationJava(handshake[2] * DurationJava.Second)
  ret.MaxConcurrent = handshake[3].toInt()
  ret.MaxBytes = Net2Host(handshake, 4, 8)
  val id1 = Net2Host(handshake, 8, 12)
  val id2 = Net2Host(handshake, 12, 16)
  ret.ConnectId = String.format("%08x", id1) + String.format("%08x", id2)

  return Pair(ret, null)
}

internal suspend fun LenContent._connect(): Pair<Protocol.Handshake, Error?> {
  return withContext(Dispatchers.IO) {
    val res = withTimeoutOrNull(optValue.connectTimeout) {
      socket.connect(InetSocketAddress(optValue.host, optValue.port)
        , optValue.connectTimeout.inWholeMilliseconds.toInt())

      val tlsRes = optValue.tls(optValue.host, optValue.port, socket)
      if (tlsRes.second != null) {
        return@withTimeoutOrNull Pair(Protocol.Handshake(), tlsRes.second)
      }

      // 发握手数据
      val outputStream = socket.getOutputStream()
      outputStream.write(handshakeReq())
      outputStream.flush()

      return@withTimeoutOrNull readHandshake(socket.getInputStream())
    }

    return@withContext res?: Pair(Protocol.Handshake()
      , Error("""LenContent._connect: timeout(${optValue.connectTimeout.inWholeSeconds}s)"""))
  }
}

internal fun LenContent._close() {

}

internal fun LenContent._send(content: ByteArray) {

}

