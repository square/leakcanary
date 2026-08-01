package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.ValueHolder.ReferenceHolder

/**
 * A heap dump can reference an object it doesn't contain: when a class fails to load, ART keeps
 * creating the matching array class and points its `$class$componentType` at the class object that
 * failed, yet never dumps that class object. See
 * [#2567](https://github.com/square/leakcanary/issues/2567).
 */
class DanglingReferenceTest {

  /**
   * An object id that no record in the heap dump defines, well above the ids the DSL hands out.
   */
  private val danglingId = 123456789L

  @Test fun `dangling static field reference is skipped`() {
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["leak"] = "Leaking" watchedInstance {}
      }
      "ArrayClass" clazz {
        staticField["\$class\$componentType"] = ReferenceHolder(danglingId)
      }
    }

    // Computing retained sizes makes the traversal exhaustive, so it reaches the array class.
    // Without it the traversal stops as soon as it has found every leaking object.
    val analysis = heapDump.checkForLeaks<HeapAnalysis>(computeRetainedHeapSize = true)

    assertThat(analysis).isInstanceOf(HeapAnalysisSuccess::class.java)
    assertThat((analysis as HeapAnalysisSuccess).applicationLeaks.flatMap { it.leakTraces })
      .hasSize(1)
  }

  @Test fun `dangling object array entry is skipped`() {
    val heapDump = dump {
      "GcRoot" clazz {
        staticField["array"] = objectArray(
          ReferenceHolder(danglingId),
          "Leaking" watchedInstance {}
        )
      }
    }

    val analysis = heapDump.checkForLeaks<HeapAnalysisSuccess>()

    assertThat(analysis.applicationLeaks.flatMap { it.leakTraces }).hasSize(1)
  }
}
