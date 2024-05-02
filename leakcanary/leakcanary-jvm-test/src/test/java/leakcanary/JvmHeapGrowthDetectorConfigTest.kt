package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HeapGraphProvider
import shark.ObjectGrowthDetector
import shark.repeatingScenario
import shark.forJvmHeap

class JvmHeapGrowthDetectorConfigTest {

  class Leaky

  private val leakies = mutableListOf<Leaky>()

  @Test
  fun `leaky increase leads to heap growth`() {
    val detector = ObjectGrowthDetector.forJvmHeap().repeatingJvmInProcessScenario()

    val growingNodes = detector.findRepeatedlyGrowingObjects {
      leakies += Leaky()
    }.growingObjects

    assertThat(growingNodes).hasSize(1)
  }
}
