package shark

import java.io.File
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StringRecord
import shark.HprofVersion.ANDROID

class HprofPrimitiveArrayStripperTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test
  fun stripHprof() {
    val sourceByteArray =
      Buffer()
        .apply {
          writeRawTestHprof(
            secretLongArray = longArrayOf(0xCAFE, 0xDAD),
            secretCharArray = charArrayOf('P', 'Y'),
            secretWrappedLong = 42,
          )
        }
        .readByteArray()

    val strippedBuffer = Buffer()
    val stripper = HprofPrimitiveArrayStripper()
    stripper.stripPrimitiveArrays(ByteArraySourceProvider(sourceByteArray), { strippedBuffer })

    val expectedByteArray =
      Buffer()
        .apply {
          writeRawTestHprof(
            secretLongArray = longArrayOf(0, 0),
            secretCharArray = charArrayOf('?', '?'),
            secretWrappedLong = 0,
          )
        }
        .readByteArray()
    assertThat(strippedBuffer.readByteArray()).isEqualTo(expectedByteArray)
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

  class Secret(val secretArray: IntArray) {
    val secretList: List<Int> = secretArray.toList()
  }

  @Test
  fun `Primitive Wrapper Types wrap 0`() {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "jvm_heap.hprof")
    val inMemorySecretArray = intArrayOf(0xCAFE, 0xDAD)
    val secret = Secret(inMemorySecretArray)
    hold(secret) { JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath) }

    val strippedFile = HprofPrimitiveArrayStripper().stripPrimitiveArrays(hprofFile)

    val (secretArray, secretListArray) = hprofFile.readSecretInArrays()
    val (strippedSecretArray, strippedSecretListArray) = strippedFile.readSecretInArrays()

    assertThat(secretArray).isEqualTo(inMemorySecretArray)
    assertThat(secretListArray).isEqualTo(inMemorySecretArray)

    val arrayOfZeros = IntArray(inMemorySecretArray.size)
    assertThat(strippedSecretArray).isEqualTo(arrayOfZeros)
    assertThat(strippedSecretListArray).isEqualTo(arrayOfZeros)
  }

  private fun Buffer.writeRawTestHprof(
    secretLongArray: LongArray,
    secretCharArray: CharArray,
    secretWrappedLong: Byte,
  ) {
    HprofWriter.openWriterFor(
        this,
        hprofHeader = HprofHeader(heapDumpTimestamp = 42, version = ANDROID, identifierByteSize = 4),
      )
      .use { writer ->
        writer.write(StringRecord(id = 1, string = "java.lang.Object"))
        writer.write(StringRecord(id = 2, string = "java.lang.Long"))
        writer.write(StringRecord(id = 3, string = "value"))
        writer.write(
          LoadClassRecord(
            classSerialNumber = 0,
            id = 1,
            stackTraceSerialNumber = 0,
            classNameStringId = 1,
          )
        )
        writer.write(
          LoadClassRecord(
            classSerialNumber = 0,
            id = 2,
            stackTraceSerialNumber = 0,
            classNameStringId = 2,
          )
        )
        writer.write(
          ClassDumpRecord(
            id = 1,
            stackTraceSerialNumber = 0,
            superclassId = 0,
            classLoaderId = 0,
            signersId = 0,
            protectionDomainId = 0,
            instanceSize = 0,
            staticFields = emptyList(),
            fields = emptyList(),
          )
        )
        writer.write(
          ClassDumpRecord(
            id = 2,
            stackTraceSerialNumber = 0,
            superclassId = 1,
            classLoaderId = 0,
            signersId = 0,
            protectionDomainId = 0,
            instanceSize = 0,
            staticFields = emptyList(),
            fields = listOf(FieldRecord(3, PrimitiveType.LONG.hprofType)),
          )
        )
        writer.write(LongArrayDump(id = 4, stackTraceSerialNumber = 0, array = secretLongArray))
        writer.write(
          InstanceDumpRecord(
            id = 5,
            classId = 2,
            stackTraceSerialNumber = 0,
            fieldValues = byteArrayOf(0, 0, 0, 0, 0, 0, 0, secretWrappedLong),
          )
        )
        writer.write(CharArrayDump(id = 6, stackTraceSerialNumber = 0, array = secretCharArray))
      }
  }

  private fun File.readSecretInArrays(): Pair<IntArray, IntArray> {
    return openHeapGraph().use { graph ->
      val className = Secret::class.java.name
      val secretInstance = graph.findClassByName(className)!!.instances.single()
      val secretArray =
        (secretInstance[className, "secretArray"]!!.valueAsPrimitiveArray!!.readRecord()
            as IntArrayDump)
          .array
      val secretListArray =
        secretInstance[className, "secretList"]!!
          .valueAsInstance!![ArrayList::class.java.name, "elementData"]!!
          .valueAsObjectArray!!
          .readElements()
          .map { arrayElement ->
            arrayElement.asObject!!
              .asInstance!![Int::class.javaObjectType.name, "value"]!!
              .value
              .asInt!!
          }
          .toList()
          .toIntArray()
      secretArray to secretListArray
    }
  }

  private class TestStringHolder(val string: String)

  private fun File.readHolderString() =
    openHeapGraph().use { graph ->
      val className = "shark.HprofPrimitiveArrayStripperTest\$TestStringHolder"
      val holderClass = graph.findClassByName(className)!!
      val holderInstance = holderClass.instances.single()
      holderInstance[className, "string"]!!.value.readAsJavaString()!!
    }

  private fun hold(held: Any, block: () -> Unit) {
    try {
      block()
    } finally {
      if (System.identityHashCode(held) * 0 > 0f) {
        error("this will never happen")
      }
    }
  }
}
