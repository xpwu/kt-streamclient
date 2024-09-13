package com.github.xpwu.stream.lencontent

import com.github.xpwu.stream.Info
import com.github.xpwu.stream.Protocol
import com.github.xpwu.x.AndroidLogger
import com.github.xpwu.x.Logger
import com.github.xpwu.x.Net2Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Objects
import kotlin.concurrent.Volatile
import kotlin.random.Random
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
    logger.Debug("LenContent.DummyDelegate.onError", error.toString())
  }

}

private fun nullOutputStream(): OutputStream {
  return object : OutputStream() {
    @Volatile
    private var closed = false

    private fun ensureOpen() {
      if (closed) {
        throw IOException("Stream closed")
      }
    }

    override fun write(b: Int) {
      ensureOpen()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      Objects.checkFromIndexSize(off, len, b.size)
      ensureOpen()
    }

    override fun close() {
      closed = true
    }
  }
}

/**
 *
 * LenContent protocol:
 *
 *     1, handshake protocol:
 *
 *                                 client ------------------ server
 *                                 |                          |
 *                                 |                          |
 *                                 ABCDEF (A^...^F = 0xff) --->  check(A^...^F == 0xff) --- N--> over
 *                                 (A is version)
 *                                 |                          |
 *                                 |                          |Y
 *                                 |                          |
 *          version 1:   set client heartbeat  <----- HeartBeat_s (2 bytes, net order)
 *          version 2:       set config     <-----  HeartBeat_s | FrameTimeout_s | MaxConcurrent | MaxBytes | connect id
 *                                                   HeartBeat_s: 2 bytes, net order
 *                                                   FrameTimeout_s: 1 byte
 *                                                   MaxConcurrent: 1 byte
 *                                                   MaxBytes: 4 bytes, net order
 *                                                   connect id: 8 bytes, net order
 *                                 |                          |
 *                                 |                          |
 *                                 |                          |
 *                                 data      <-------->       data
 *
 *
 *     2, data protocol:
 *        1) length | content
 *        length: 4 bytes, net order; length=sizeof(content)+4; length=0 => heartbeat
 *
 */

class LenContent(vararg options: Option) : Protocol {

  internal val optValue: OptionValue = OptionValue()
  internal var logger: Logger = AndroidLogger()
  internal var delegate: Protocol.Delegate = DummyDelegate(logger)

  internal val heartbeatStop: Channel<Boolean> = Channel(UNLIMITED)

  internal var socket = Socket()
  internal val outputMutex: Mutex = Mutex()
  internal var outputStream = nullOutputStream()

  internal var handshake: Protocol.Handshake = Protocol.Handshake()
  internal val connectID: String get() = handshake.ConnectId

  internal val flag = Random.nextLong()

  init {
    for (op in options) {
      op.runner(optValue)
    }
  }

  override suspend fun connect(): Pair<Protocol.Handshake, Error?> {
    return _connect()
  }

  override suspend fun close() {
    _close()
  }

  override suspend fun send(content: ByteArray): Error? {
    return _send(content)
  }

  override fun setDelegate(delegate: Protocol.Delegate) {
    this.delegate = delegate
  }

  override fun setLogger(logger: Logger) {
    this.logger = logger
    val ph = Integer.toHexString(this.hashCode())
    logger.Debug("LenContent[$flag].new", "hashcode=$ph")
  }
}

private fun handshakeReq(): ByteArray {
  val handshake = ByteArray(6)
  Random.nextBytes(handshake)

  // version is 2
  handshake[0] = 2
  handshake[5] = 0xff.toByte()
  for (i in 0..4) {
    handshake[5] = (handshake[5].toInt() xor (handshake[i]).toInt()).toByte()
  }

  return handshake
}

private fun LenContent.readHandshake(inputStream: InputStream): Pair<Protocol.Handshake, Error?> {
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
      logger.Debug("LenContent[$flag].readHandshake:error", "maybe connection closed by peer or timeout")
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

  this.handshake = ret

  logger.Debug("LenContent[$flag]<$connectID>.readHandshake:handshake", this.handshake.Info())

  return Pair(ret, null)
}

private suspend fun LenContent.receiveInputStream() {
  withContext(Dispatchers.IO) {

    val inputStream: InputStream
    try {
      inputStream = socket.getInputStream()
    } catch (e: SocketException) {
      logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:getInputStream", "error --- ${e.message}")
      this@receiveInputStream.delegate.onError(Error(e.message?:"get inputstream error, maybe connection closed by peer"))
      return@withContext
    } catch (e: IOException) {
      logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:getInputStream", "error --- ${e.message}")
      this@receiveInputStream.delegate.onError(Error(e.toString()))
      return@withContext
    }

    launch {
      logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:start", "run loop...")
      while (!socket.isClosed && socket.isConnected) {
        var heartbeatTimeout = true
        try {
          val lengthB = ByteArray(4)
          var pos = 0

          heartbeatTimeout = true
          socket.soTimeout = handshake.HearBeatTime.times(2).inWholeMilliseconds.toInt()
          // 先读一个，表示有数据了
          var n: Int = inputStream.read(lengthB, pos, 1)
          if (n <= 0) {
            if (!socket.isClosed) {
              this@receiveInputStream.delegate.onError(Error("inputstream read-1 error, maybe connection closed by peer"))
            }
            logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:read-1", "error: n<=0")
            break
          }
          pos += n

          heartbeatTimeout = false
          socket.soTimeout = handshake.FrameTimeout.inWholeMilliseconds.toInt()
          while (4 - pos != 0 && n > 0) {
            n = inputStream.read(lengthB, pos, 4 - pos)
            pos += n
          }
          if (n <= 0) {
            logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:read-4", "error: n<=0")
            if (!socket.isClosed) {
              this@receiveInputStream.delegate.onError(Error("inputstream read-4 error, maybe connection closed by peer"))
            }
            break
          }

          pos = 0
          var length = (((0xff and lengthB[0].toInt()).toLong() shl 24)
            + ((0xff and lengthB[1].toInt()) shl 16)
            + ((0xff and lengthB[2].toInt()) shl 8)
            + ((0xff and lengthB[3].toInt())))
          if (length == 0L) { // heartbeat
            logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:Heartbeat", "receive heartbeat from server")
            continue
          }

          length -= 4
          // todo: server must use this MaxBytes value also
          if (length > this@receiveInputStream.handshake.MaxBytes) {
            logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:MaxBytes"
              , "error: data(len: $length > maxbytes: ${handshake.MaxBytes}) is Too Large")
            this@receiveInputStream.delegate.onError(
              Error("""received Too Large data(len=$length), must be less than ${this@receiveInputStream.handshake.MaxBytes}"""))
            break
          }

          val data = ByteArray(length.toInt())
          while (length - pos != 0L && n > 0) {
            socket.soTimeout = handshake.FrameTimeout.inWholeMilliseconds.toInt()
            n = inputStream.read(data, pos, length.toInt() - pos)
            pos += n
          }
          if (n <= 0) {
            logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:read-n", "error: n<=0")
            if (!socket.isClosed) {
              this@receiveInputStream.delegate.onError(Error("inputstream read-n error, maybe connection closed by peer"))
            }
            break
          }

          logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:read", "read one message")
          this@receiveInputStream.delegate.onMessage(data)

        } catch (e: SocketTimeoutException) {
          logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:timeout", "error: ${if(heartbeatTimeout)"Heartbeat" else "Frame"}-timeout")
          this@receiveInputStream.delegate.onError(
            Error("""LenContent.receiveInputStream---${if(heartbeatTimeout)"Heartbeat" else "Frame"}-timeout: ${e.message?:e.toString()}"""))
          break
        } catch (e: SocketException) {
          logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:error", e.message?:"unknown")
          this@receiveInputStream.delegate.onError(Error(e.message?:"LenContent.receiveInputStream---SocketException"))
          break
        } catch (e: Exception) {
          logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:error", e.message?:"unknown")
          this@receiveInputStream.delegate.onError(Error(e.toString()))
          break
        }
      }
      logger.Debug("LenContent[$flag]<$connectID>.receiveInputStream:end", "run loop is end")
    }
  }
}

internal suspend fun LenContent._connect(): Pair<Protocol.Handshake, Error?> {
  val r = withTimeoutOrNull(optValue.connectTimeout) {
    withContext(Dispatchers.IO) {
      logger.Debug("LenContent[$flag]._connect:start", "start")
      try {
        socket.connect(InetSocketAddress(optValue.host, optValue.port)
          , optValue.connectTimeout.inWholeMilliseconds.toInt())

        val tlsRes = optValue.tls(optValue.host, optValue.port, socket)
        if (tlsRes.second != null) {
          logger.Debug("LenContent[$flag]._connect:tls"
            , "error: ${tlsRes.second!!.message?:"unknown"}")
          return@withContext Pair(Protocol.Handshake(), tlsRes.second)
        }
        socket = tlsRes.first

        outputMutex.lock()
        outputStream = socket.getOutputStream()
        outputMutex.unlock()

        // 发握手数据
        outputStream.write(handshakeReq())
        outputStream.flush()

        return@withContext readHandshake(socket.getInputStream())

      } catch (e: Exception) {
        logger.Debug("LenContent[$flag]._connect:error", e.message?:"unknown")
        return@withContext Pair(Protocol.Handshake(), Error(e.message?:e.toString()))
      }
    }
  }

  r?.let {
    if (it.second != null) {
      logger.Debug("LenContent[$flag]._connect:error", it.second!!.message?:"unknown")
      return it
    }
    receiveInputStream()
    setOutputHeartbeat()

    logger.Debug("LenContent[$flag]<$connectID>._connect:end", "connectID = $connectID")
    return it
  }

  logger.Debug("LenContent[$flag]._connect:timeout"
    , "timeout(${optValue.connectTimeout.inWholeSeconds}s)")
  return Pair(Protocol.Handshake()
    , Error("""LenContent._connect: timeout(${optValue.connectTimeout.inWholeSeconds}s)"""))
}

internal fun LenContent._close() {
  try {
    if (socket.isClosed) {
      return
    }
    logger.Debug("LenContent[$flag]<$connectID>._close", "closed")
    heartbeatStop.close()
    socket.close()
  } catch (e: Exception) {
   logger.Error("LenContent[$flag]<$connectID>._close:error", e.toString())
  }
}

/**
 * stop 后必须调用 set
 * heartbeat 后必须再次调用
 * 可以多发 heartbeat，但不能不发 heartbeat
 */
private suspend fun LenContent.stopOutputHeartbeat() {
  try {
    logger.Debug("LenContent[$flag]<$connectID>.outputHeartbeatTimer", "will stop")
    heartbeatStop.send(true)
  } catch (e: ClosedReceiveChannelException) {
    return
  }
}

private suspend fun LenContent.setOutputHeartbeat() {
  logger.Debug("LenContent[$flag]<$connectID>.outputHeartbeatTimer:set", "set")
  val ret = withTimeoutOrNull(this.handshake.HearBeatTime) {
    try {
      heartbeatStop.receive()
    } catch (e: ClosedReceiveChannelException) {
      return@withTimeoutOrNull true
    }
  }

  // stopped
  ret?.let {
    logger.Debug("LenContent[$flag]<$connectID>.outputHeartbeatTimer:stopped", "stopped")
    return
  }

  // timeout
  withContext(Dispatchers.IO) {
    logger.Debug("LenContent[$flag]<$connectID>.outputHeartbeatTimer:send", "send heartbeat to server")
    try {
      outputMutex.lock()
      outputStream.write(ByteArray(4){0})
    }catch (e: Exception) {
      this@setOutputHeartbeat.delegate.onError(Error(e.message?:e.toString()))
    }finally {
      outputMutex.unlock()
    }

    launch {
      setOutputHeartbeat()
    }
  }
}

internal suspend fun LenContent._send(content: ByteArray): Error? {
  if (content.size > this.handshake.MaxBytes) {
    return Error("""request.size(${content.size}) > MaxBytes(${this.handshake.MaxBytes})""")
  }

  withContext(Dispatchers.IO) {
    try {
      logger.Debug("LenContent[$flag]<$connectID>._send:start", "data size = ${content.size}")
      stopOutputHeartbeat()
      outputMutex.lock()
      outputStream.write(content)
      logger.Debug("LenContent[$flag]<$connectID>._send:end", "end")
    } catch (e: Exception) {
      logger.Debug("LenContent[$flag]<$connectID>._send:error", e.message?:"unknown")
      this@_send.delegate.onError(Error(e.message?:e.toString()))
    } finally {
      outputMutex.unlock()
      setOutputHeartbeat()
    }
  }

  return null
}

