package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.Companion.NULL_REFERENCE
import shark.ValueHolder.ReferenceHolder

class ObjectArrayReferenceReaderTest {

  @Test fun `array entry is named after its index in the array`() {
    val heapDump = dump {
      val element = instance(clazz("Element"))
      objectArray(nullReference, nullReference, element)
    }

    val references = heapDump.readArrayReferenceNames()

    assertThat(references).containsExactly("2")
  }

  @Test fun `entry missing from the heap dump doesn't shift the index of later entries`() {
    val heapDump = dump {
      val element = instance(clazz("Element"))
      objectArray(danglingReference, element)
    }

    val references = heapDump.readArrayReferenceNames()

    assertThat(references).containsExactly("1")
  }

  @Test fun `array references can be read more than once`() {
    val heapDump = dump {
      val element = instance(clazz("Element"))
      objectArray(nullReference, element, nullReference, element)
    }

    heapDump.openHeapGraph().use { graph ->
      val references = ObjectArrayReferenceReader().read(graph.singleObjectArray)

      assertThat(references.referenceNames()).isEqualTo(references.referenceNames())
      assertThat(references.referenceNames()).containsExactly("1", "3")
    }
  }

  private val nullReference = ReferenceHolder(NULL_REFERENCE)

  /**
   * An id no object of the heap dump has: [HprofWriterHelper] hands out ids counting up from 1.
   */
  private val danglingReference = ReferenceHolder(Long.MAX_VALUE)

  private fun DualSourceProvider.readArrayReferenceNames(): List<String> {
    return openHeapGraph().use { graph ->
      ObjectArrayReferenceReader().read(graph.singleObjectArray).referenceNames()
    }
  }

  private val HeapGraph.singleObjectArray: HeapObject.HeapObjectArray
    get() = objectArrays.single()

  private fun Sequence<Reference>.referenceNames(): List<String> =
    map { it.lazyDetailsResolver.resolve().name }.toList()
}
