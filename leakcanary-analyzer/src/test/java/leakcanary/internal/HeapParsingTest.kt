package leakcanary.internal

import com.android.tools.perflib.captures.MemoryMappedFileBuffer
import com.squareup.haha.perflib.Snapshot
import okio.BufferedSource
import okio.buffer
import okio.source
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.Date

class HeapParsingTest {

  @Test fun findKeyedWeakReferenceClassInHeapDump() {
    val file = fileFromName(HeapDumpFile.ASYNC_TASK_P.filename)

    val keyedWeakReferenceId = file.source()
        .buffer()
        .use {
          with(it) {
            val nullIndex = indexOf(0)
            val version = readUtf8(nullIndex)
            // 0
            readByte()

            val idSize = readInt()

            val heapdumpTimestamp = readLong()
            val heapDumpDate = Date(heapdumpTimestamp)

            println("$version $idSize $heapDumpDate")

            // TODO Experiment with not storing anything and navigating around the file.
            val stringIdByString = mutableMapOf<String, Long>()
            val classIdByClassNameId = mutableMapOf<Long, Long>()
            while (!it.exhausted()) {
              // type of the record
              val tag = readUnsignedByte()

              // number of microseconds since the time stamp in the header
              readInt()

              // number of bytes that follow and belong to this record
              val length = readUnsignedInt()

              when (tag) {
                STRING_IN_UTF8 -> {
                  val id = readId(idSize)
                  val string = readUtf8(length - idSize)
                  stringIdByString[string] = id
                }
                LOAD_CLASS -> {
                  // class serial number (don't care, used for stack frames)
                  readInt()
                  val id = readId(idSize)
                  // stack trace serial number (don't care)
                  readInt()
                  val classNameStringId = readId(idSize)
                  classIdByClassNameId[classNameStringId] = id
                }
                STACK_FRAME -> {
                  skip(length)
                }
                STACK_TRACE -> {
                  skip(length)
                }
                HEAP_DUMP -> {
                  skip(length)
                }
                HEAP_DUMP_SEGMENT -> {
                  skip(length)
                }
                else -> {
                  skip(length)
                }
              }
            }
            val stringId = stringIdByString["com.squareup.leakcanary.KeyedWeakReference"]
            classIdByClassNameId[stringId]!!
          }
        }
    val buffer = MemoryMappedFileBuffer(file)
    val snapshot = Snapshot.createSnapshot(buffer)

    val keyedWeakReferenceClass = snapshot.findClass("com.squareup.leakcanary.KeyedWeakReference")
    assertThat(keyedWeakReferenceId).isEqualTo(keyedWeakReferenceClass.id)
  }
}

fun BufferedSource.readUnsignedInt(): Long {
  return readInt().toLong() and INT_MASK
}

fun BufferedSource.readUnsignedByte(): Int {
  return readByte().toInt() and BYTE_MASK
}

private fun BufferedSource.readId(idSize: Int): Long {
  // As long as we don't interpret IDs, reading signed values here is fine.
  return when (idSize) {
    1 -> readByte().toLong()
    2 -> readShort().toLong()
    4 -> readInt().toLong()
    8 -> readLong()
    else -> throw IllegalArgumentException("ID Length must be 1, 2, 4, or 8")
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
