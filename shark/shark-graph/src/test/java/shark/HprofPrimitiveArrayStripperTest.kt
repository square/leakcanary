@file:OptIn(ExperimentalStdlibApi::class)

package shark

import java.io.File
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.CHAR
import java.io.IOException
import okio.BufferedSource
import shark.DualSourceProvider
import shark.HprofHeader
import shark.HprofRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord
import shark.HprofRecordTag
import shark.HprofRecordTag.CLASS_DUMP
import shark.HprofRecordTag.HEAP_DUMP
import shark.HprofRecordTag.HEAP_DUMP_END
import shark.HprofRecordTag.LOAD_CLASS
import shark.HprofRecordTag.STRING_IN_UTF8
import shark.HprofVersion.ANDROID
import shark.HprofWriter
import shark.PrimitiveType
import shark.RandomAccessSource
import shark.StreamingHprofReader
import java.util.EnumSet
import okio.ByteString.Companion.toByteString

class HprofPrimitiveArrayStripperTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  private var lastId = 0L
  private val id: Long
    get() = ++lastId

  @OptIn(ExperimentalStdlibApi::class)
  @Test fun foo() {
    val sourceByteArray = Buffer().apply {
      writeRawTestHprof()
    }.readByteArray()

    val working = Buffer().apply {
      HprofWriter.openWriterFor(
        this,
        hprofHeader = HprofHeader(heapDumpTimestamp = 42, version = ANDROID, identifierByteSize = 4)
      ).use { writer ->
        writer.write(StringRecord(1, "java.lang.Object"))
        writer.write(StringRecord(2, "java.lang.Long"))
        writer.write(StringRecord(3, "value"))
        writer.write(LoadClassRecord(0, 1, 0, 1))
        writer.write(
          ClassDumpRecord(
            1,
            0,
            0,
            0,
            0,
            0,
            0,
            emptyList(),
            emptyList(),
            // listOf(FieldRecord(3, PrimitiveType.LONG.hprofType))
          )
        )
      }
      // println(this.snapshot(this.size.toInt()).toString())
    }.readByteArray()

    println(sourceByteArray.toByteString().hex())
    println(working.toByteString().hex())

    assertThat(sourceByteArray).isEqualTo(working)

    val sourceProvider = ByteArraySourceProvider(sourceByteArray)

    val bytesRead = StreamingHprofReader.readerFor(sourceProvider)
      .readRecords(EnumSet.allOf(HprofRecordTag::class.java)) { tag, length, reader ->
        println("Got tag $tag length $length")
        when (tag) {
          STRING_IN_UTF8 -> {
            val record = reader.readStringRecord(length)
            println("string record: id ${record.id} [${record.string}]")
          }

          LOAD_CLASS -> {
            val record = reader.readLoadClassRecord()
            println("class record: id ${record.id} string id [${record.classNameStringId}]")
          }

          else -> {
            reader.skip(length)
          }
        }
      }

    println("Bytes read: $bytesRead")

    sourceProvider.openHeapGraph().use { graph ->
    }

    val strippedBuffer = Buffer()
    val stripper = HprofPrimitiveArrayStripper()
    stripper.stripPrimitiveArrays(sourceProvider, strippedBuffer)

    val expectedByteArray = sourceByteArray
    assertThat(strippedBuffer.readByteArray()).isEqualTo(expectedByteArray)
  }

  private fun Buffer.writeRawTestHprof() {
    writeUtf8(ANDROID.versionString)
    writeByte(0)
    writeInt(TEST_ID_BYTE_SIZE)
    // heapDumpTimestamp
    writeLong(42)

    val objectStringId = 1
    writeStringRecord("java.lang.Object", objectStringId)
    val longStringId = 2
    writeStringRecord("java.lang.Long", longStringId)
    val valueStringId = 3
    writeStringRecord("value", valueStringId)

    println(snapshot().toByteArray().toByteString().hex())

    // java.lang.Object
    val objectClassId = 1
    writeLoadClassRecord(objectClassId, objectStringId)
    val longClassId = 2
    // writeLoadClassRecord(longClassId, longStringId)

    println(snapshot().toByteArray().toByteString().hex())
    writeHeapDumpRecord {
      // writeByte(ROOT_DEBUGGER.tag)
      // writeInt(0)

      writeClassDumpRecord(
        classId = objectClassId,
        superclassId = 0
      )
      // writeClassDumpRecord(
      //   classId = longClassId,
      //   superclassId = objectClassId,
      //   fieldsStringIdAndType = listOf(valueStringId to PrimitiveType.LONG.hprofType)
      // )
      println(this)
    }

    println("before heap dump end")
    println(snapshot().toByteArray().toByteString().hex())
    writeByte(HEAP_DUMP_END.tag)
    // Timestamp
    writeInt(0)
    // length
    writeInt(0)
    println("Final state")
    // println(snapshot().toByteArray().toHexString())

    // println(this.snapshot(this.size.toInt()).toString())
  }

  private fun Buffer.writeStringRecord(
    string: String,
    id: Int
  ) {
    writeByte(STRING_IN_UTF8.tag)
    // Timestamp
    writeInt(0)
    val byteArray = string.toByteArray()
    // length
    writeInt(TEST_ID_BYTE_SIZE + byteArray.size)
    // string id
    writeInt(id)
    write(byteArray)
  }

  private fun Buffer.writeLoadClassRecord(
    classId: Int,
    classNameStringId: Int
  ) {
    writeByte(LOAD_CLASS.tag)
    // Timestamp
    writeInt(0)
    // length
    writeInt(16)
    // classSerialNumber
    writeInt(0)
    writeInt(classId)
    // stackTraceSerialNumber
    writeInt(0)
    writeInt(classNameStringId)
  }

  private fun Buffer.writeHeapDumpRecord(writeHeapDump: Buffer.() -> Unit) {
    writeByte(HEAP_DUMP.tag)
    // Timestamp
    writeInt(0)
    val heapDumpBuffer = Buffer()
    heapDumpBuffer.writeHeapDump()
    // length
    writeInt(heapDumpBuffer.size.toInt())
    write(heapDumpBuffer.readByteArray())
  }

  private fun Buffer.writeClassDumpRecord(
    classId: Int,
    superclassId: Int,
    fieldsStringIdAndType: List<Pair<Int, Int>> = emptyList()
  ) {
    // After the 2nd print we're adding:
    // 0c000000000000002b20000000010000000000000000000000000000000000000000000000000000000000000000000000000000
    // But we expect
    // 0c000000000000003020000000010000000000000000000000000000000000000000000000000000000000000000000000000001000000030b
    writeByte(CLASS_DUMP.tag)
    writeInt(classId)
    // stackTraceSerialNumber
    writeInt(0)
    // superclassId
    writeInt(superclassId)
    // class loader object ID
    writeInt(0)
    // signers object ID
    writeInt(0)
    // protection domain object ID
    writeInt(0)
    // reserved
    writeInt(0)
    // reserved
    writeInt(0)
    // instance size (in bytes)
    writeInt(0)
    // constant pool size
    writeShort(0)
    // static field count
    writeShort(0)
    // field count
    writeShort(fieldsStringIdAndType.size)
    for ((id, type) in fieldsStringIdAndType) {
      writeInt(id)
      writeByte(type)
    }
  }

  @Test
  fun stripHprof() {
    val booleanArray = BooleanArrayDump(id, 1, booleanArrayOf(true, false, true, true))
    val charArray = CharArrayDump(id, 1, "Hello World!".toCharArray())
    val hprofBytes = listOf(booleanArray, charArray).asHprofBytes()

    val stripper = HprofPrimitiveArrayStripper()

    val strippedBuffer = Buffer()
    stripper.stripPrimitiveArrays(hprofBytes, strippedBuffer)

    val strippedSource = ByteArraySourceProvider(strippedBuffer.readByteArray())

    strippedSource.openHeapGraph().use { graph ->
      val booleanArrays = graph.objects
        .filter { it is HeapPrimitiveArray && it.primitiveType == BOOLEAN }
        .map { it.readRecord() as BooleanArrayDump }
        .toList()
      assertThat(booleanArrays).hasSize(1)
      assertThat(booleanArrays[0].id).isEqualTo(booleanArray.id)
      assertThat(booleanArrays[0].array).isEqualTo(booleanArrayOf(false, false, false, false))

      val charArrays = graph.objects
        .filter { it is HeapPrimitiveArray && it.primitiveType == CHAR }
        .map { it.readRecord() as CharArrayDump }
        .toList()
      assertThat(charArrays).hasSize(1)
      assertThat(charArrays[0].id).isEqualTo(charArray.id)
      assertThat(charArrays[0].array).isEqualTo("????????????".toCharArray())
    }
  }

  @Test
  fun `ByteArray based String content is replaced with question marks`() {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")
    val stringSavedToDump = "Yo!"
    hold(TestStringHolder(stringSavedToDump)) {
      JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    }

    val strippedFile = HprofPrimitiveArrayStripper().stripPrimitiveArrays(hprofFile)

    val initialString = hprofFile.readHolderString()
    val strippedString = strippedFile.readHolderString()
    assertThat(initialString).isEqualTo(stringSavedToDump)
    assertThat(strippedString).isEqualTo("?".repeat(stringSavedToDump.length))
  }

  @Test
  fun `input file deleted after stripping`() {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")
    val stringSavedToDump = "Yo!"
    hold(TestStringHolder(stringSavedToDump)) {
      JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    }

    val strippedFile =
      HprofPrimitiveArrayStripper().stripPrimitiveArrays(hprofFile, deleteInputHprofFile = true)
    assertThat(hprofFile.exists()).isFalse()
    // Ensures stripped file exists and can be read
    assertThat(strippedFile.readHolderString()).isEqualTo("?".repeat(stringSavedToDump.length))
  }

  class Secret(
    val secretArray: IntArray
  ) {
    val secretList: List<Int> = secretArray.toList()
  }

  @Test
  fun `Primitive Wrapper Types wrap 0`() {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")
    val inMemorySecretArray = intArrayOf(0xCAFE, 0xDAD)
    val secret = Secret(inMemorySecretArray)
    hold(secret) {
      JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    }

    val strippedFile = HprofPrimitiveArrayStripper().stripPrimitiveArrays(hprofFile)

    val (secretArray, secretListArray) = hprofFile.readSecretInArrays()
    val (strippedSecretArray, strippedSecretListArray) = strippedFile.readSecretInArrays()

    assertThat(secretArray).isEqualTo(inMemorySecretArray)
    assertThat(secretListArray).isEqualTo(inMemorySecretArray)

    val arrayOfZeros = IntArray(inMemorySecretArray.size)
    assertThat(strippedSecretArray).isEqualTo(arrayOfZeros)
    assertThat(strippedSecretListArray).isEqualTo(arrayOfZeros)
  }

  private fun File.readSecretInArrays(): Pair<IntArray, IntArray> {
    return openHeapGraph().use { graph ->
      val className = Secret::class.java.name
      val secretInstance = graph.findClassByName(className)!!.instances.single()
      val secretArray = (secretInstance[className, "secretArray"]!!
        .valueAsPrimitiveArray!!
        .readRecord() as IntArrayDump).array
      val secretListArray = secretInstance[className, "secretList"]!!
        .valueAsInstance!![ArrayList::class.java.name, "elementData"]!!
        .valueAsObjectArray!!
        .readElements()
        .map { arrayElement ->
          arrayElement.asObject!!
            .asInstance!![Int::class.javaObjectType.name, "value"]!!
            .value
            .asInt!!
        }.toList()
        .toIntArray()
      secretArray to secretListArray
    }
  }

  private class TestStringHolder(val string: String)

  private fun File.readHolderString() = openHeapGraph().use { graph ->
    val className = "shark.HprofPrimitiveArrayStripperTest\$TestStringHolder"
    val holderClass = graph.findClassByName(className)!!
    val holderInstance = holderClass.instances.single()
    holderInstance[className, "string"]!!.value.readAsJavaString()!!
  }

  private fun hold(
    held: Any,
    block: () -> Unit
  ) {
    try {
      block()
    } finally {
      if (System.identityHashCode(held) * 0 > 0f) {
        error("this will never happen")
      }
    }
  }

  companion object {
    private const val TEST_ID_BYTE_SIZE = 4
  }
}

class ByteArraySourceProvider(private val byteArray: ByteArray) : DualSourceProvider {
  override fun openStreamingSource(): BufferedSource = Buffer().apply { write(byteArray) }

  override fun openRandomAccessSource(): RandomAccessSource {
    return object : RandomAccessSource {

      var closed = false

      override fun read(
        sink: Buffer,
        position: Long,
        byteCount: Long
      ): Long {
        if (closed) {
          throw IOException("Source closed")
        }
        val maxByteCount = byteCount.coerceAtMost(byteArray.size - position)
        sink.write(byteArray, position.toInt(), maxByteCount.toInt())
        return maxByteCount
      }

      override fun close() {
        closed = true
      }
    }
  }
}

fun List<HprofRecord>.asHprofBytes(): DualSourceProvider {
  val buffer = Buffer()
  HprofWriter.openWriterFor(buffer)
    .use { writer ->
      forEach { record ->
        writer.write(record)
      }
    }
  return ByteArraySourceProvider(buffer.readByteArray())
}
