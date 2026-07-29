package shark

import java.io.File
import java.util.EnumSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ObjectDominators.DominatorNode
import shark.ValueHolder.Companion.NULL_REFERENCE
import shark.ValueHolder.ReferenceHolder

class ObjectDominatorsTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `an ignored reference does not retain its target`() {
    weaklyReachablePayloadHeapDump().openHeapGraph().use { graph ->
      val tree = ObjectDominators().buildDominatorTree(graph, REFERENCE_STRENGTH_MATCHERS)

      assertThat(tree.classNames(graph)).doesNotContain(PAYLOAD_CLASS_NAME)
      assertThat(tree.getValue(NULL_REFERENCE).retainedSize).isLessThan(PAYLOAD_BYTE_SIZE)
    }
  }

  @Test fun `not ignoring a reference has it retain its target`() {
    // The same heap dump with no ignored references, so that the assertion above is about the
    // parameter being applied rather than about the payload being unreachable for some other reason.
    weaklyReachablePayloadHeapDump().openHeapGraph().use { graph ->
      val tree = ObjectDominators().buildDominatorTree(graph, emptyList())

      assertThat(tree.classNames(graph)).contains(PAYLOAD_CLASS_NAME)
      assertThat(tree.getValue(NULL_REFERENCE).retainedSize).isGreaterThan(PAYLOAD_BYTE_SIZE)
    }
  }

  /** A heap dump where a large object array is only reachable through a `WeakReference`. */
  private fun weaklyReachablePayloadHeapDump(): File {
    val file = testFolder.newFile("weakly-reachable.hprof")
    file.dump {
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val weakReference = "java.lang.ref.WeakReference" instance {
        field["referent"] = payload
      }
      gcRoot(JniGlobal(id = weakReference.value, jniGlobalRefId = 0))
    }
    return file
  }

  /**
   * The class name of every object in the tree, with class objects prefixed so that the
   * `java.lang.Object[]` class isn't mistaken for an instance of it.
   */
  private fun Map<Long, DominatorNode>.classNames(graph: HeapGraph): List<String> =
    keys.filter { it != NULL_REFERENCE }
      .map { objectId ->
        when (val heapObject = graph.findObjectById(objectId)) {
          is HeapObject.HeapClass -> "class ${heapObject.name}"
          is HeapObject.HeapInstance -> heapObject.instanceClassName
          is HeapObject.HeapObjectArray -> heapObject.arrayClassName
          is HeapObject.HeapPrimitiveArray -> heapObject.arrayClassName
        }
      }

  companion object {
    private const val PAYLOAD_CLASS_NAME = "java.lang.Object[]"
    private const val PAYLOAD_ELEMENT_COUNT = 1024

    /** Object ids are 4 bytes in a dump built by the test DSL. */
    private const val PAYLOAD_BYTE_SIZE = PAYLOAD_ELEMENT_COUNT * 4L

    /** The matchers that say which references don't keep their target alive. */
    private val REFERENCE_STRENGTH_MATCHERS =
      ReferenceMatcher.fromListBuilders(EnumSet.of(JdkReferenceMatchers.REFERENCES))
        .map { it as IgnoredReferenceMatcher }
  }
}
