package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class UnreachableObjectRenderingTest {

  @Test fun `renders unreachable object`() {
    val heapDump = dump {
      "com.example.SomeClass" watchedInstance {
      }
    }

    val analysis = heapDump.checkForLeaks<HeapAnalysisSuccess>()

    analysis renders """
    com.example.SomeClass instance
    _  Leaking: YES (ObjectWatcher was watching this because its lifecycle has ended)
    _  key = 39efcc1a-67bf-2040-e7ab-3fc9f94731dc
    _  watchDurationMillis = 25000
    _  retainedDurationMillis = 10000
        """
  }

  private infix fun HeapAnalysisSuccess.renders(expectedString: String) {
    assertThat(unreachableObjects[0].toString()).isEqualTo(
      expectedString.trimIndent().replace('_', LeakTrace.ZERO_WIDTH_SPACE)
    )
  }

}