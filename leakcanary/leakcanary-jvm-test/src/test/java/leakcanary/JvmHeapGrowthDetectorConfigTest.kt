package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HeapGraphProvider
import shark.ObjectGrowthDetector
import shark.fromHeapDumpingRepeatedScenario
import shark.jvmDetector

class JvmHeapGrowthDetectorConfigTest {

  class Leaky

  private val leakies = mutableListOf<Leaky>()

  @Test
  fun `leaky increase leads to heap growth`() {
    val detector = ObjectGrowthDetector.jvmDetector()
      .fromHeapDumpingRepeatedScenario(
        heapGraphProvider = HeapGraphProvider.dumpingAndDeletingGraphProvider(
          heapDumper = HeapDumper.jvmDumper()
        )
      )

    val growingNodes = detector.findRepeatedlyGrowingObjects {
      leakies += Leaky()
    }.growingNodes

    assertThat(growingNodes).hasSize(1)
  }
}
