package shark

import okio.BufferedSink
import okio.BufferedSource
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT

class CopyingSource(
  private val source: BufferedSource,
  private val sink: BufferedSink
) {
  var bytesRead = 0L
    private set

  fun transfer(byteCount: Int) {
    transfer(byteCount.toLong())
  }

  fun indexOf(b: Byte) = source.indexOf(b)

  fun transfer(byteCount: Long) {
    bytesRead += byteCount
    sink.write(source, byteCount)
  }

  fun transferUnsignedByte(): Int {
    bytesRead++
    val value = source.readByte().toInt()
    sink.writeByte(value)
    val valueAsUnsignedByte = value and 0xff
    return valueAsUnsignedByte
  }

  fun transferInt(): Int {
    bytesRead += INT.byteSize
    return source.readInt().apply {
      sink.writeInt(this)
    }
  }

  fun transferShort(): Int {
    bytesRead += SHORT.byteSize
    return source.readShort().toInt().apply {
      sink.writeShort(this)
    }
  }

  fun transferLong(): Long {
    bytesRead += LONG.byteSize
    return source.readLong().apply {
      sink.writeLong(this)
    }
  }

  fun transferByte(): Byte {
    bytesRead++
    return source.readByte().apply {
      sink.writeByte(toInt())
    }
  }

  fun transferUnsignedInt(): Long {
    bytesRead += INT.byteSize
    val value = source.readInt()
    sink.writeInt(value)
    val unsignedIntValue = value.toLong() and 0xffffffffL
    return unsignedIntValue
  }

  fun transferUnsignedShort(): Int {
    bytesRead += SHORT.byteSize
    val value = source.readShort().toInt()
    sink.writeShort(value)
    val unsignedShortValue = value and 0xFFFF
    return unsignedShortValue
  }

  fun transferUtf8(byteCount: Long): String {
    bytesRead += byteCount
    val bytes = source.readByteArray(byteCount)
    sink.write(bytes)
    return bytes.toString(Charsets.UTF_8)
  }

  fun overwrite(byteArray: ByteArray) {
    val byteCount = byteArray.size.toLong()
    bytesRead += byteCount
    source.skip(byteCount)
    sink.write(byteArray)
  }

  fun exhausted() = source.exhausted()
}
