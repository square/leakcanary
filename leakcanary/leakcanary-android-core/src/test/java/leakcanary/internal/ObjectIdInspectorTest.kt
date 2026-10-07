package leakcanary.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ObjectReporter
import shark.ValueHolder.ReferenceHolder
import shark.dump

class ObjectIdInspectorTest {

  @Test fun `labels an object with its id in hexadecimal`() {
    val label = labelOfInstanceAt(objectId = 0x12c4a8f0L)

    assertThat(label).isEqualTo("Object id: 0x12c4a8f0")
  }

  @Test fun `spells an object above the 2 GB mark of a 32 bit heap dump with all 16 digits`() {
    val label = labelOfInstanceAt(objectId = HIGH_ADDRESS_OBJECT_ID)

    assertThat(label).isEqualTo("Object id: 0xffffffff82000010")
  }

  @Test fun `an object id reads back as the object it labels, the way Shark Dive reads one`() {
    listOf(0x12c4a8f0L, HIGH_ADDRESS_OBJECT_ID).forEach { objectId ->
      val label = labelOfInstanceAt(objectId)

      val readBack = java.lang.Long.parseUnsignedLong(label.removePrefix("Object id: 0x"), 16)

      assertThat(readBack).isEqualTo(objectId)
    }
  }

  /** The label [ObjectIdInspector] gives an instance written at [objectId] of a heap dump. */
  private fun labelOfInstanceAt(objectId: Long): String {
    val heapDump = dump {
      instance(clazz("com.example.Leaking"), objectId = ReferenceHolder(objectId))
    }
    return heapDump.openHeapGraph().use { graph ->
      val reporter = ObjectReporter(graph.findObjectById(objectId))
      ObjectIdInspector.inspect(reporter)
      reporter.labels.single()
    }
  }

  private companion object {
    /**
     * An object at 0x82000010 of a heap dump with 4 byte ids, as shark reads it: widened by sign,
     * since it's past the 2 GB mark.
     */
    const val HIGH_ADDRESS_OBJECT_ID = 0x82000010L.toInt().toLong()
  }
}
