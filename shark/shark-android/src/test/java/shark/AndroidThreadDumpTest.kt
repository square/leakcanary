package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph

class AndroidThreadDumpTest {

  /**
   * Android heap dumps don't contain stack trace records, so there's no thread dump to expose
   * even though they do contain thread objects.
   */
  @Test fun `android heap dump has no threads`() {
    "leak_asynctask_o.hprof".classpathFile().openHeapGraph().use { graph ->
      assertThat(graph.threads.toList()).isEmpty()
    }
  }
}
