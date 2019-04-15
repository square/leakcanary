package leakcanary.internal

import com.android.tools.perflib.captures.DataBuffer
import com.android.tools.perflib.captures.MemoryMappedFileBuffer
import com.squareup.haha.perflib.Snapshot
import okio.buffer
import okio.source
import org.assertj.core.api.Assertions.assertThat
import org.junit.Ignore
import org.junit.Test
import sun.nio.ch.DirectBuffer
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Date

class HeapParsingTest {


  /*
           * Heap parsing gives us ids and positions
           * We first parse strings ids. As we parse through, we note which strings we may need
           * (class names, field names, etc) and keep those and discard the rest.
           * Though we can start with keeping all string ids.
           *
           * We need a bi map for string ids. We could probably use a trie for string => id
           * and LongSparceArray for the other way round. Two maps will do the trick until then
           *
           * Then, we need "find all instances of class X". That means: find the X string id,
           * then find the id of the class that has that string id, then find all instances that
           * have that class id.
           *
           * => we keep bi map of class id to string id
           * => we keep list of heaps and position of those heaps => that's just a list of long
           *
           * Then for each heap we go through all instances and find the ones that have the
           * expected class id.
           *
           * Then we need to read the content of each of those instance.
           *
           * For each class in the class hierarchy, we read all the fields. static fields
           * have their value, member fields are defined here.
           *
           * Maybe we keep an index of class id => position so that we can quickly read a class
           * content?
           *
           * We keep an lru cache of class data.
           *
           * We need a list of instances of a given class, as well as either their content
           * or a pointer to read it later. We'll probably start with reading all of the content,
           * as we'll use that to navigate? Maybe better to start by assuming nothing is
           * available and everything has to be read and then we can prefetch
           */

  @Test fun findKeyedWeakReferenceClassInHeapDump() {
    val file = fileFromName(HeapDumpFile.ASYNC_TASK_P.filename)

    val inputStream = file.inputStream()
    val channel = inputStream.channel

    val keyedWeakReferenceId = inputStream.source()
        .buffer()
        .use {
          val nullIndex = it.indexOf(0)
          val version = it.readUtf8(nullIndex)
          // 0
          it.readByte()
          val idSize = it.readInt()
          var startPosition = nullIndex + 2
          println("$version $idSize")
          HprofParser(channel, it, startPosition, idSize)
              .parseHeapdump()
        }
    val buffer = MemoryMappedFileBuffer(file)
    val snapshot = Snapshot.createSnapshot(buffer)

    val keyedWeakReferenceClass = snapshot.findClass("com.squareup.leakcanary.KeyedWeakReference")
    assertThat(keyedWeakReferenceId).isEqualTo(keyedWeakReferenceClass.id)
  }

  private fun HprofParser.parseHeapdump(): Long {

    val stringId = stringIdByString["com.squareup.leakcanary.KeyedWeakReference"]
    return classIdByClassNameId[stringId]!!
  }

  @Test fun reset() {
    val file = fileFromName(HeapDumpFile.ASYNC_TASK_P.filename)

    val inputStream = file.inputStream()
    val channel = inputStream.channel

    inputStream.source()
        .buffer()
        .use {
          with(it) {
            val version1 = readUtf8(indexOf(0))
            buffer.clear()
            channel.position(0)
            val version2 = readUtf8(indexOf(0))
            buffer.clear()
            channel.position(5)
            val justProfile = readUtf8(indexOf(0))
            assertThat(version2).isEqualTo(version1)
            assertThat(version1).isEqualTo("JAVA PROFILE 1.0.3")
            assertThat(justProfile).isEqualTo("PROFILE 1.0.3")
          }
        }
  }

  @Test @Ignore("resetting does not currently work") fun memoryMappedReset() {
    val file = fileFromName(HeapDumpFile.ASYNC_TASK_P.filename)

    val inputStream = file.memoryMappedInputStream()
    inputStream.source()
        .buffer()
        .use {
          with(it) {
            val version1 = readUtf8(indexOf(0))
            inputStream.position = 0
            val version2 = readUtf8(indexOf(0))
            assertThat(version2).isEqualTo(version1)
            assertThat(version1).isEqualTo("JAVA PROFILE 1.0.3")
          }
        }
  }
}

fun File.memoryMappedInputStream(): MemoryMappedFileInputStream =
  MemoryMappedFileInputStream(
      this, DEFAULT_MEMORY_MAPPED_BUFFER_SIZE, DEFAULT_MEMORY_MAPPED_PADDING
  )

class MemoryMappedFileInputStream(
  file: File,
  private val bufferSize: Int,
  private val padding: Int
) : InputStream() {

  var position = 0

  private val length = file.length()

  private val byteBuffers: Array<ByteBuffer>

  private val index
    get() = position / bufferSize

  private val offset
    get() = position % bufferSize

  init {
    val buffersCount = (length / bufferSize).toInt() + 1
    val byteBuffers = arrayOfNulls<ByteBuffer>(buffersCount)

    FileInputStream(file).use { inputStream ->
      var offset: Long = 0
      for (i in 0 until buffersCount) {
        val size = Math.min(length - offset, (bufferSize + padding).toLong())

        byteBuffers[i] = inputStream.channel
            .map(FileChannel.MapMode.READ_ONLY, offset, size)
            .order(DataBuffer.HPROF_BYTE_ORDER)
        offset += bufferSize.toLong()
      }
    }

    @Suppress("UNCHECKED_CAST")
    this.byteBuffers = byteBuffers as Array<ByteBuffer>
  }

  override fun close() {
    try {
      for (i in byteBuffers.indices) {
        (byteBuffers[i] as DirectBuffer).cleaner()
            .clean()
      }
    } catch (ex: Exception) {
      ex.printStackTrace()
      TODO("Check this works on Android")
      // ignore, this is a best effort attempt.
    }
  }

  override fun read(): Int {
    TODO("not supported")
  }

  override fun read(b: ByteArray): Int {
    TODO("not supported")
  }

  override fun read(
    buffer: ByteArray,
    bufferOffset: Int,
    length: Int
  ): Int {
    println("Read $length bytes with offset $bufferOffset")
    if (length == 0) return 0
    require(length > 0) { "length < 0: $length" }
    require(
        length < DEFAULT_MEMORY_MAPPED_BUFFER_SIZE
    ) { "$length > $DEFAULT_MEMORY_MAPPED_BUFFER_SIZE" }
    require(buffer.size - bufferOffset >= length) { "${buffer.size} - $bufferOffset < $length" }
    byteBuffers[index].position(offset)
    val bytesRead: Int
    if (length <= byteBuffers[index].remaining()) {
      byteBuffers[index].get(buffer, bufferOffset, length)
      bytesRead = length
    } else {
      if (index == byteBuffers.lastIndex) {
        bytesRead = byteBuffers[index].remaining()
        byteBuffers[index].get(buffer, bufferOffset, bytesRead)
      } else {
        // Wrapped read
        val split = bufferSize - byteBuffers[index].position()
        byteBuffers[index].get(buffer, bufferOffset, split)
        byteBuffers[index + 1].position(0)

        val readInNextBuffer = length - split

        val remainingInNextBuffer = byteBuffers[index + 1].remaining()
        if (remainingInNextBuffer < readInNextBuffer) {
          bytesRead = split + remainingInNextBuffer
          byteBuffers[index + 1].get(buffer, bufferOffset + split, remainingInNextBuffer)
        } else {
          byteBuffers[index + 1].get(buffer, bufferOffset + split, readInNextBuffer)
          bytesRead = length
        }
      }
    }
    position += bytesRead

    return bytesRead
  }
}

const val INT_MASK = 0xffffffffL
const val BYTE_MASK = 0xFF

const val STRING_IN_UTF8 = 0x01
const val LOAD_CLASS = 0x02
const val UNLOAD_CLASS = 0x03
const val STACK_FRAME = 0x04
const val STACK_TRACE = 0x05
const val ALLOC_SITES = 0x06
const val HEAP_SUMMARY = 0x07
const val START_THREAD = 0x0a
const val END_THREAD = 0x0b
const val HEAP_DUMP = 0x0c
const val HEAP_DUMP_SEGMENT = 0x1c
// Default chunk size is 1 << 30, or 1,073,741,824 bytes.
const val DEFAULT_MEMORY_MAPPED_BUFFER_SIZE = 1 shl 30

// Eliminate wrapped, multi-byte reads across chunks in most cases.
const val DEFAULT_MEMORY_MAPPED_PADDING = 1024

const val HEAP_DUMP_END = 0x2c

const val CPU_SAMPLES = 0x0d

const val CONTROL_SETTINGS = 0x0e

const val ROOT_UNKNOWN = 0xff

const val ROOT_JNI_GLOBAL = 0x01

const val ROOT_JNI_LOCAL = 0x02

const val ROOT_JAVA_FRAME = 0x03

const val ROOT_NATIVE_STACK = 0x04

const val ROOT_STICKY_CLASS = 0x05

const val ROOT_THREAD_BLOCK = 0x06

const val ROOT_MONITOR_USED = 0x07

const val ROOT_THREAD_OBJECT = 0x08

const val CLASS_DUMP = 0x20

const val INSTANCE_DUMP = 0x21

const val OBJECT_ARRAY_DUMP = 0x22

const val PRIMITIVE_ARRAY_DUMP = 0x23

/**
 * Android format addition
 *
 * Specifies information about which heap certain objects came from. When a sub-tag of this type
 * appears in a HPROF_HEAP_DUMP or HPROF_HEAP_DUMP_SEGMENT record, entries that follow it will
 * be associated with the specified heap.  The HEAP_DUMP_INFO data is reset at the end of the
 * HEAP_DUMP[_SEGMENT].  Multiple HEAP_DUMP_INFO entries may appear in a single
 * HEAP_DUMP[_SEGMENT].
 *
 * Format: u1: Tag value (0xFE) u4: heap ID ID: heap name string ID
 */
const val HEAP_DUMP_INFO = 0xfe

const val ROOT_INTERNED_STRING = 0x89

const val ROOT_FINALIZING = 0x8a

const val ROOT_DEBUGGER = 0x8b

const val ROOT_REFERENCE_CLEANUP = 0x8c

const val ROOT_VM_INTERNAL = 0x8d

const val ROOT_JNI_MONITOR = 0x8e

const val ROOT_UNREACHABLE = 0x90

const val PRIMITIVE_ARRAY_NODATA = 0xc3