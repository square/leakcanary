package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.ObjectGrowthDetector
import shark.forJvmHeap

class JvmHeapGrowthDetectorConfigTest {

  class Leaky

  private val leakies = mutableListOf<Leaky>()

  @Test
  fun `leaky increase leads to heap growth`() {
    val detector = ObjectGrowthDetector.forJvmHeap().repeatingJvmInProcessScenario(
      maxHeapDumps = 2,
      scenarioLoopsPerDump = 10
    )

    val growingNodes = detector.findRepeatedlyGrowingObjects {
      leakies += Leaky()
    }.growingObjects

    assertThat(growingNodes).hasSize(1)
  }
}
