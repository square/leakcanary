package leakcanary.internal

import com.android.tools.perflib.captures.DataBuffer
import com.android.tools.perflib.captures.MemoryMappedFileBuffer
import com.squareup.haha.perflib.ArrayInstance
import com.squareup.haha.perflib.Snapshot
import leakcanary.internal.haha.HeapValue
import leakcanary.internal.haha.HeapValue.IntValue
import leakcanary.internal.haha.HeapValue.ObjectReference
import leakcanary.internal.haha.HprofParser
import leakcanary.internal.haha.HprofParser.HydradedInstance
import leakcanary.internal.haha.HprofParser.RecordCallbacks
import leakcanary.internal.haha.Record.HeapDumpRecord.InstanceDumpRecord
import leakcanary.internal.haha.Record.LoadClassRecord
import leakcanary.internal.haha.Record.StringRecord
import okio.buffer
import okio.source
import org.assertj.core.api.Assertions.assertThat
import org.junit.Ignore
import org.junit.Test
import sun.nio.ch.DirectBuffer
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

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

    val before1 = System.nanoTime()
    val parser = HprofParser.open(file)

    var keyedWeakReferenceStringId = -1L
    var keyedWeakReferenceClassId = -1L
    val keyedWeakReferenceInstances = mutableListOf<InstanceDumpRecord>()
    val callbacks = RecordCallbacks()
        .on(StringRecord::class.java) {
          if (it.string == "com.squareup.leakcanary.KeyedWeakReference") {
            keyedWeakReferenceStringId = it.id
          }
        }
        .on(LoadClassRecord::class.java) {
          if (it.classNameStringId == keyedWeakReferenceStringId) {
            keyedWeakReferenceClassId = it.id
          }
        }
        .on(InstanceDumpRecord::class.java) {
          if (it.classId == keyedWeakReferenceClassId) {
            keyedWeakReferenceInstances.add(it)
          }
        }
    parser.scan(callbacks)

    keyedWeakReferenceInstances.forEach {
      val instance = parser.hydrate(it)
      println("####### Dump for ${instance.record.id} #######")
      instance.classHierarchy.forEachIndexed { classIndex, hydradedClass ->
        hydradedClass.fieldNames.forEachIndexed { fieldIndex, name ->
          val value = instance.fieldValues[classIndex][fieldIndex]
          println("$name = $value")
        }
      }
    }

    keyedWeakReferenceInstances.map { parser.hydrate(it) }
        .firstOrNull { instance ->
          val keyFieldIndex = instance.classHierarchy[0].fieldNames.indexOfFirst { it == "key" }
          val keyFieldValue = instance.fieldValues[0][keyFieldIndex] as ObjectReference
          val keyString = parser.hydratedInstanceById(keyFieldValue.value)


          true
        }

    parser.close()

    val after1 = System.nanoTime()

    val buffer = MemoryMappedFileBuffer(file)
    val snapshot = Snapshot.createSnapshot(buffer)

    val keyedWeakReferenceClass = snapshot.findClass("com.squareup.leakcanary.KeyedWeakReference")

    val after2 = System.nanoTime()

    println("First: ${(after1 - before1) / 1000000}ms Second: ${(after2 - after1) / 1000000}ms")
    assertThat(keyedWeakReferenceClassId).isEqualTo(keyedWeakReferenceClass.id)
  }

  fun HydradedInstance.asString(parser: HprofParser): String {
    val stringClass = classHierarchy[0]
    val fieldMap = stringClass.fieldNames.mapIndexed { index, name -> name to index }
        .toMap()

    fun field(name: String): HeapValue =
      fieldValues[0][fieldMap.getValue("count")]

    val count = field("count") as IntValue

    if (count.value == 0) {
      return ""
    }

    val value = field("value") as ObjectReference

    var offset: Int?
    val array: ArrayInstance

    if (HahaHelper.isCharArray(value)) {
      array = value as ArrayInstance

      offset = 0
      // < API 23
      // As of Marshmallow, substrings no longer share their parent strings' char arrays
      // eliminating the need for String.offset
      // https://android-review.googlesource.com/#/c/83611/
      if (HahaHelper.hasField(values, "offset")) {
        offset = HahaHelper.fieldValue<Int>(values, "offset")!!
      }

      val chars = array.asCharArray(offset, count)
      return String(chars)
    } else if (HahaHelper.isByteArray(value)) {
      // In API 26, Strings are now internally represented as byte arrays.
      array = value as ArrayInstance

      // HACK - remove when HAHA's perflib is updated to https://goo.gl/Oe7ZwO.
      try {
        val asRawByteArray = ArrayInstance::class.java.getDeclaredMethod(
            "asRawByteArray", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        asRawByteArray.isAccessible = true
        val rawByteArray = asRawByteArray.invoke(array, 0, count) as ByteArray
        return String(rawByteArray, Charset.forName("UTF-8"))
      } catch (e: NoSuchMethodException) {
        throw RuntimeException(e)
      } catch (e: IllegalAccessException) {
        throw RuntimeException(e)
      } catch (e: InvocationTargetException) {
        throw RuntimeException(e)
      }

    } else {
      throw UnsupportedOperationException("Could not find char array in $this")
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

// Default chunk size is 1 << 30, or 1,073,741,824 bytes.
const val DEFAULT_MEMORY_MAPPED_BUFFER_SIZE = 1 shl 30

// Eliminate wrapped, multi-byte reads across chunks in most cases.
const val DEFAULT_MEMORY_MAPPED_PADDING = 1024
