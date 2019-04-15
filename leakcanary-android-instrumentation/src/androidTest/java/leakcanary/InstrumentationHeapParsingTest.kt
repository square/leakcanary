package leakcanary

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.android.tools.perflib.captures.MemoryMappedFileBuffer
import com.squareup.haha.perflib.ClassObj
import com.squareup.haha.perflib.RootObj
import com.squareup.haha.perflib.Snapshot
import gnu.trove.THashMap
import gnu.trove.TObjectProcedure
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Test
import java.io.File
import java.util.Date

/** Intrusmentation test so that we can profile the android memory here */
class HeapParsingTest {

  @Test fun findKeyedWeakReferenceClassInHeapDump() {
    CanaryLog.d("Started")
    SystemClock.sleep(10000)
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val filesDir = instrumentation.targetContext.filesDir
    val file = File(filesDir, "heapdump")

    CanaryLog.d("Heap Dump")
    Debug.dumpHprofData(file.absolutePath)

    CanaryLog.d("Reading with okio")

    val actualClassId = file.source()
        .buffer().use {
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

            val rootObjectIds = mutableSetOf<Long>()

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
                HEAP_DUMP, HEAP_DUMP_SEGMENT -> {
                  skip(length)
                }
                else -> {
                  skip(length)
                }
              }
            }
            val stringId = stringIdByString["leakcanary.HeapParsingTest"]
            classIdByClassNameId[stringId]!!
          }
        }

    CanaryLog.d("Waiting before reading with perflib")
    SystemClock.sleep(5000)
    GcTrigger.Default.runGc()
    SystemClock.sleep(5000)

    CanaryLog.d("now reading with perflib")

    val expectedClass = getWithPerflib(file)

    SystemClock.sleep(5000)
    GcTrigger.Default.runGc()
    SystemClock.sleep(5000)

    if (actualClassId != expectedClass.id) {
      throw AssertionError("id was $actualClassId instead of  ${expectedClass.id}")
    }
  }

  private fun getWithPerflib(file: File): ClassObj {
    val buffer = MemoryMappedFileBuffer(file)
    val snapshot = Snapshot.createSnapshot(buffer)

    CanaryLog.d("Done parsing, deduplicating gc roots")
    SystemClock.sleep(2000)
    deduplicateGcRoots(snapshot)
    CanaryLog.d("Done deduplicating gc roots")

    SystemClock.sleep(2000)
    GcTrigger.Default.runGc()
    SystemClock.sleep(2000)

    val expectedClass = snapshot.findClass("leakcanary.HeapParsingTest")

    CanaryLog.d("Disposing")
    snapshot.dispose()
    return expectedClass
  }
}

private fun generateRootKey(root: RootObj): String {
  return String.format("%s@0x%08x", root.rootType.getName(), root.id)
}

/**
 * Pruning duplicates reduces memory pressure from hprof bloat added in Marshmallow.
 */
internal fun deduplicateGcRoots(snapshot: Snapshot) {
  // THashMap has a smaller memory footprint than HashMap.
  val uniqueRootMap = THashMap<String, RootObj>()

  val gcRoots = snapshot.gcRoots
  for (root in gcRoots) {
    val key = generateRootKey(root)
    if (!uniqueRootMap.containsKey(key)) {
      uniqueRootMap[key] = root
    }
  }

  // Repopulate snapshot with unique GC roots.
  gcRoots.clear()
  uniqueRootMap.forEach(TObjectProcedure { key -> gcRoots.add(uniqueRootMap[key]) })
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