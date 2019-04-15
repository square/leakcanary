package leakcanary.internal

import leakcanary.internal.HprofParser.HeapRecord.StringRecord
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel

/**
 * Not thread safe, should be used from a single thread.
 */
class HprofParser private constructor(
  private val channel: FileChannel,
  private val source: BufferedSource,
  private val startPosition: Long,
  private val idSize: Int
) : Closeable {

  class RecordCallbacks {

    private val callbacks = mutableMapOf<Class<out HeapRecord>, Any>()

    fun <T : HeapRecord> on(
      recordClass: Class<T>,
      callback: (T) -> Unit
    ): RecordCallbacks {
      callbacks[recordClass] = callback
      return this
    }

    fun <T : HeapRecord> get(recordClass: Class<T>): ((T) -> Unit)? {
      @Suppress("UNCHECKED_CAST")
      return callbacks.get(recordClass) as ((T) -> Unit)?
    }

    inline fun <reified T : HeapRecord> get(): ((T) -> Unit)? {
      return get(T::class.java)
    }

  }

  sealed class HeapRecord {
    data class StringRecord(
      val id: Long,
      val string: String
    ) : HeapRecord()
  }

  private val typeSizes = mapOf(
      // object
      2 to idSize,
      // boolean
      4 to 1,
      // char
      5 to 2,
      // float
      6 to 4,
      // double
      7 to 8,
      // byte
      8 to 1,
      // short
      9 to 2,
      // int
      10 to 4,
      // long
      11 to 8
  )

  private var position: Long = startPosition

  private fun moveTo(newPosition: Long) {
    source.buffer.clear()
    channel.position(newPosition)
    position = newPosition
  }

  private fun readShort(): Short {
    position += 2
    return source.readShort()
  }

  private fun readInt(): Int {
    position += 4
    return source.readInt()
  }

  private fun skipInt() {
    skip(4L)
  }

  private fun skipLong() {
    skip(8L)
  }

  private fun readLong(): Long {
    position += 8
    return source.readLong()
  }

  private fun exhausted() = source.exhausted()

  private fun skip(byteCount: Long) {
    position += byteCount
    return source.skip(byteCount)
  }

  private fun readByte(): Byte {
    position++
    return source.readByte()
  }

  private fun readId(): Long {
    // As long as we don't interpret IDs, reading signed values here is fine.
    return when (idSize) {
      1 -> readByte().toLong()
      2 -> readShort().toLong()
      4 -> readInt().toLong()
      8 -> readLong()
      else -> throw IllegalArgumentException("ID Length must be 1, 2, 4, or 8")
    }
  }

  private fun readUtf8(byteCount: Long): String {
    position += byteCount
    return source.readUtf8(byteCount)
  }

  private fun readUnsignedInt(): Long {
    return readInt().toLong() and INT_MASK
  }

  private fun readUnsignedByte(): Int {
    return readByte().toInt() and BYTE_MASK
  }

  private fun readUnsignedShort(): Int {
    return readShort().toInt() and 0xFFFF
  }

  private fun skip(byteCount: Int) {
    position += byteCount
    return source.skip(byteCount.toLong())
  }

  override fun close() {
    source.close()
  }

  fun scan(callbacks: RecordCallbacks) {
    if (!source.isOpen) {
      throw IllegalStateException("Source closed")
    }
    moveTo(startPosition)

    // heap dump timestamp
    skipLong()

    while (!exhausted()) {
      // type of the record
      val tag = readUnsignedByte()

      // number of microseconds since the time stamp in the header
      skipInt()

      // number of bytes that follow and belong to this record
      val length = readUnsignedInt()

      when (tag) {
        STRING_IN_UTF8 -> {

          when(callbacks.get<StringRecord>()) {
            s
          }

          val callback = callbacks.get<StringRecord>()
          if (callback != null) {
            val id = readId()
            val string = readUtf8(length - idSize)
            callback(StringRecord(id, string))
          } else {
            skip(length)
          }

        }
        LOAD_CLASS -> {
          // class serial number (don't care, used for stack frames)
          readInt()
          val id = readId()
          // stack trace serial number (don't care)
          readInt()
          val classNameStringId = readId()
        }
        HEAP_DUMP, HEAP_DUMP_SEGMENT -> {
          val heapDumpStart = position
          var previousTag = 0
          while (position - heapDumpStart < length) {
            val heapDumpTag = readUnsignedByte()

            when (heapDumpTag) {
              // UNKNOWN
              ROOT_UNKNOWN -> {
                //  Ignored root
                readId()
              }

              // A global variable in native code.
              ROOT_JNI_GLOBAL -> {
                // Referred instance id
                rootObjectIds.add(readId())
                // JNI global ref ID (ignored)
                readId()
              }

              // A local variable in native code.
              ROOT_JNI_LOCAL -> {
                rootObjectIds.add(readId())
                // thread serial number
                readInt()
                // frame number in stack trace (-1 for empty)
                readInt()
              }

              // JAVA_LOCAL
              ROOT_JAVA_FRAME -> {
                rootObjectIds.add(readId())
                // thread serial number
                readInt()
                // frame number in stack trace (-1 for empty)
                readInt()
              }

              // Input or output parameters in native code
              ROOT_NATIVE_STACK -> {
                rootObjectIds.add(readId())
                // thread serial number
                // Thread is sometimes not found, see:
                // https://issuetracker.google.com/issues/122713143
                readInt()
              }

              // System class
              ROOT_STICKY_CLASS -> {
                rootObjectIds.add(readId())
              }

              // An object that was referenced from an active thread block.
              ROOT_THREAD_BLOCK -> {
                rootObjectIds.add(readId())
                // thread serial number
                readInt()
              }

              // Everything that called the wait() or notify() methods, or
              // that is synchronized.
              ROOT_MONITOR_USED -> {
                rootObjectIds.add(readId())
              }

              // Added at https://android.googlesource.com/platform/tools/base/+/c0f0d528c155cab32e372dac77370569a386245c
              ROOT_THREAD_OBJECT -> {
                //  Ignored root TODO Why ignored?
                readId()
                // thread serial number
                readInt()
                // stack trace serial number
                readInt()
              }

              CLASS_DUMP -> {
                val id = readId()
                // stack trace serial number
                readInt()
                val superClassId = readId()
                // class loader object ID
                readId()
                // signers object ID
                readId()
                // protection domain object ID
                readId()
                // reserved
                readId()
                // reserved
                readId()

                // instance size (in bytes)
                // Useful to compute retained size
                readInt()

                //  Skip over the constant pool
                val constantPoolCount = readUnsignedShort()
                for (i in 0 until constantPoolCount) {
                  // constant pool index
                  readUnsignedShort()
                  val type = readUnsignedByte()
                  skip(typeSizes.getValue(type))
                }

                val staticFieldCount = readUnsignedShort()

                for (i in 0 until staticFieldCount) {
                  // name string id
                  readId()
                  val type = readUnsignedByte()
                  // value
                  skip(typeSizes.getValue(type))
                }

                //  Instance fields
                val fieldCount = readUnsignedShort()
                for (i in 0 until fieldCount) {
                  // name string id
                  readId()
                  // Field type
                  readUnsignedByte()
                }
              }

              INSTANCE_DUMP -> {
                val id = readId()
                // stack trace serial number
                readInt()
                // class object ID
                readId()
                val remainingBytesInInstance = readInt()
                skip(remainingBytesInInstance)
              }

              OBJECT_ARRAY_DUMP -> {
                val id = readId()
                // stack trace serial number
                readInt()
                // length
                val arrayLength = readInt()
                // array class object ID
                readId()
                // elements
                skip(arrayLength * idSize)
              }

              PRIMITIVE_ARRAY_DUMP -> {
                val id = readId()
                // stack trace serial number
                readInt()
                // length
                val arrayLength = readInt()
                val type = readUnsignedByte()
                skip(arrayLength * typeSizes.getValue(type))
              }

              PRIMITIVE_ARRAY_NODATA -> {
                throw UnsupportedOperationException(
                    "PRIMITIVE_ARRAY_NODATA cannot be parsed"
                )
              }

              HEAP_DUMP_INFO -> {
                // heap id
                readInt()
                // heap name string id
                readId()
              }

              // INTERNED_STRING (ignored root)
              ROOT_INTERNED_STRING -> {
                readId()
              }

              // An object that is in a queue, waiting for a finalizer to run.
              ROOT_FINALIZING -> {
                // Ignored root
                readId()
              }

              ROOT_DEBUGGER -> {
                // Ignored root
                readId()
              }

              // TODO What is this and why do we care about it as a root?
              ROOT_REFERENCE_CLEANUP -> {
                rootObjectIds.add(readId())
              }

              // VM_INTERNAL
              ROOT_VM_INTERNAL -> {
                rootObjectIds.add(readId())
              }

              ROOT_JNI_MONITOR -> {
                rootObjectIds.add(readId())
                // stack trace serial number
                readInt()
                // stack depth
                readInt()
              }

              // An object that is unreachable from any other root, but not a root itself.
              ROOT_UNREACHABLE -> {
                //  Ignored root
                readId()
              }
              else -> throw IllegalStateException(
                  "Unknown tag $heapDumpTag after $previousTag"
              )
            }
            previousTag = heapDumpTag
          }
        }
        else -> {
          skip(length)
        }
      }
    }
  }

  companion object {
    fun open(heapDump: File): HprofParser {
      val inputStream = heapDump.inputStream()
      val channel = inputStream.channel
      val source = inputStream.source()
          .buffer()
      val endOfVersionString = source.indexOf(0)
      source.skip(endOfVersionString + 1)
      val idSize = source.readInt()
      val startPosition = endOfVersionString + 2
      return HprofParser(channel, source, startPosition, idSize)
    }
  }

}