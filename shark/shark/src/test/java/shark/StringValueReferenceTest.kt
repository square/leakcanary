package shark

import java.io.File
import kotlin.reflect.KClass
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ReferencePattern.InstanceFieldPattern
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder

/**
 * Indexing picks up the id of the array holding the characters of every string as it reads a heap
 * dump, so that reading the one reference of the most numerous kind of instance costs no read. These
 * are the cases where what indexing captured has to match what reading the instance record would
 * have found, and the cases where it captures nothing so that reading falls back to the record.
 */
class StringValueReferenceTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `the value array is found when it isn't the first field`() {
    val file = stringHeapDump(
      fields = listOf(
        "count" to IntHolder::class,
        "hash" to IntHolder::class,
        "value" to ReferenceHolder::class
      ),
      fieldValues = { value -> listOf(IntHolder(5), IntHolder(0), value) }
    )

    file.openHeapGraph().use { graph ->
      val reference = FieldInstanceReferenceReader(graph, emptyList())
        .read(graph.stringInstance)
        .single()

      assertThat(reference.valueObjectId).isEqualTo(graph.valueArrayId)
      assertThat(reference.lazyDetailsResolver.resolve().name).isEqualTo("value")
    }
  }

  @Test fun `a string with no value array has no reference`() {
    val file = stringHeapDump(
      fields = listOf(
        "value" to ReferenceHolder::class,
        "count" to IntHolder::class
      ),
      fieldValues = { listOf(ReferenceHolder(ValueHolder.NULL_REFERENCE), IntHolder(0)) }
    )

    file.openHeapGraph().use { graph ->
      val references = FieldInstanceReferenceReader(graph, emptyList())
        .read(graph.stringInstance)
        .toList()

      assertThat(references).isEmpty()
    }
  }

  @Test fun `a string class with two reference fields has both read`() {
    // Indexing only picks the array up when java.lang.String holds exactly one reference, so here
    // it captures nothing and reading the instance record has to find both references.
    val file = stringHeapDump(
      fields = listOf(
        "value" to ReferenceHolder::class,
        "unexpected" to ReferenceHolder::class
      ),
      fieldValues = { value -> listOf(value, value) }
    )

    file.openHeapGraph().use { graph ->
      val references = FieldInstanceReferenceReader(graph, emptyList())
        .read(graph.stringInstance)
        .toList()

      assertThat(references.map { it.lazyDetailsResolver.resolve().name })
        .containsExactly("unexpected", "value")
      assertThat(references.map { it.valueObjectId }.distinct())
        .containsExactly(graph.valueArrayId)
    }
  }

  @Test fun `a matcher on the value field is applied`() {
    val file = stringHeapDump(
      fields = listOf(
        "value" to ReferenceHolder::class,
        "count" to IntHolder::class
      ),
      fieldValues = { value -> listOf(value, IntHolder(5)) }
    )
    val ignored = IgnoredReferenceMatcher(InstanceFieldPattern("java.lang.String", "value"))

    file.openHeapGraph().use { graph ->
      val references = FieldInstanceReferenceReader(graph, listOf(ignored))
        .read(graph.stringInstance)
        .toList()

      assertThat(references).isEmpty()
    }
  }

  /** A heap dump holding a single string, of a java.lang.String class shaped as [fields]. */
  private fun stringHeapDump(
    fields: List<Pair<String, KClass<out ValueHolder>>>,
    fieldValues: (ReferenceHolder) -> List<ValueHolder>
  ): File {
    val file = testFolder.newFile("string-${fields.joinToString("-") { it.first }}.hprof")
    file.dump {
      val stringClassId = clazz(className = "java.lang.String", fields = fields)
      instance(stringClassId, fieldValues("Hello".charArrayDump))
    }
    return file
  }

  private val HeapGraph.stringInstance: HeapInstance
    get() = instances.single { it.instanceClassName == "java.lang.String" }

  private val HeapGraph.valueArrayId: Long
    get() = objects.single { it is HeapPrimitiveArray }.objectId
}
