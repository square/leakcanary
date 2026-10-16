package shark

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.LeakTraceReference.ReferenceType.ARRAY_ENTRY
import shark.LeakTraceReference.ReferenceType.INSTANCE_FIELD
import shark.LeakTraceReference.ReferenceType.STATIC_FIELD

/**
 * The 4 byte ids of an Android heap dump reach past the 2 GB mark, where an id with its top bit set
 * reads as a negative [Int]. Shark reads it as the address it is, like every other heap dump tool.
 */
class HighAddressObjectIdsTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  @Test fun `a leak trace runs through objects above the 2 GB mark of a 32 bit heap dump`() {
    val file = testFolder.newFile("high.hprof")
    file.dump(firstObjectId = FIRST_HIGH_ADDRESS) {
      val leaking = "com.example.Leaking".watchedInstance {}
      val holder = "com.example.Holder" instance {
        field["items"] = objectArray(leaking)
      }
      "com.example.Singleton" clazz {
        staticField["holder"] = holder
      }
    }

    val analysis = file.checkForLeaks<HeapAnalysisSuccess>()

    val leakTrace = analysis.applicationLeaks.single().leakTraces.single()
    assertThat(leakTrace.referencePath.map { it.referenceType to it.referenceName })
      .containsExactly(
        STATIC_FIELD to "holder",
        INSTANCE_FIELD to "items",
        ARRAY_ENTRY to "0"
      )
    file.openHeapGraph().use { graph ->
      val singleton = graph.findClassByName("com.example.Singleton")!!
      val holder = singleton["holder"]!!.valueAsInstance!!
      val items = holder["com.example.Holder", "items"]!!.valueAsObjectArray!!
      val leaking = items.readElements().single().asObject!!
      assertThat(listOf(singleton, holder, items, leaking).map { it.objectId })
        .allMatch { it > FIRST_HIGH_ADDRESS }
      assertThat(leaking.asInstance!!.instanceClassName).isEqualTo("com.example.Leaking")
    }
  }

  @Test fun `a sign widened id doesn't fit in a 32 bit heap dump`() {
    val file = testFolder.newFile("negative.hprof")

    assertThatThrownBy {
      file.dump(firstObjectId = FIRST_HIGH_ADDRESS.toInt().toLong()) {}
    }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageStartingWith("Object id 0xffffffff82000000 doesn't fit in the 4 bytes")
  }

  private companion object {
    const val FIRST_HIGH_ADDRESS = 0x82000000L
  }
}
