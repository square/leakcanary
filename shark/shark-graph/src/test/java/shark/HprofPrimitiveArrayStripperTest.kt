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

class HprofPrimitiveArrayStripperTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  private var lastId = 0L
  private val id: Long
    get() = ++lastId

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
}
