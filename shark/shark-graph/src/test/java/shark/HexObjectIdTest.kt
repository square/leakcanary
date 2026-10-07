package shark

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph

class HexObjectIdTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  @Test fun `an object is written with its id in hex`() {
    val file = testFolder.newFile("low.hprof")
    file.dump {
      "com.example.Holder" instance {}
    }

    file.openHeapGraph().use { graph ->
      val holder = graph.findClassByName("com.example.Holder")!!.instances.single()

      assertThat(holder.toString()).matches("instance @0x[0-9a-f]+ of com\\.example\\.Holder")
    }
  }

  @Test fun `an object above the 2 GB mark of a 32 bit heap dump is written as its address`() {
    val file = testFolder.newFile("high.hprof")
    file.dump(firstObjectId = FIRST_HIGH_ADDRESS) {
      "com.example.Holder" instance {}
    }

    file.openHeapGraph().use { graph ->
      val holder = graph.findClassByName("com.example.Holder")!!.instances.single()

      // Read back widened by sign, which written as is would be 0x-7dffff..
      assertThat(holder.objectId).isNegative()
      assertThat(holder.toString()).matches("instance @0x820000[0-9a-f]{2} of com\\.example\\.Holder")
    }
  }

  @Test fun `an id that isn't in the heap dump is written in hex`() {
    val file = testFolder.newFile("missing.hprof")
    file.dump {
      "com.example.Holder" instance {}
    }

    file.openHeapGraph().use { graph ->
      assertThatThrownBy { graph.findObjectById(0x12c0a6b8) }
        .hasMessage("Object id 0x12c0a6b8 not found in heap dump.")
    }
  }

  private companion object {
    /** 0x82000000 as shark reads a 4 byte id. */
    const val FIRST_HIGH_ADDRESS = -0x7E000000L
  }
}
