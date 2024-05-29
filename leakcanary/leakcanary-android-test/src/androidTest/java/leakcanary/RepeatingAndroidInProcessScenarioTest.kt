package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HeapDiff
import shark.RepeatingScenarioObjectGrowthDetector.Companion.DEFAULT_MAX_HEAP_DUMPS
import shark.RepeatingScenarioObjectGrowthDetector.Companion.DEFAULT_SCENARIO_LOOPS_PER_DUMP

class RepeatingAndroidInProcessScenarioTest {

  private val growingList = mutableListOf<String>()

  @Test fun failing_scenario_iterates_to_max_heap_dumps_times_loops_per_dump() {
    val detector = HeapDiff.repeatingAndroidInProcessScenario()
    var iteration = 0
    detector.findRepeatedlyGrowingObjects {
      growingList += "growth $iteration"
      iteration++
    }

    assertThat(iteration).isEqualTo(DEFAULT_MAX_HEAP_DUMPS * DEFAULT_SCENARIO_LOOPS_PER_DUMP)
  }

  @Test fun failing_scenario_finds_expected_growing_object() {
    val detector = HeapDiff.repeatingAndroidInProcessScenario()
    var iteration = 0
    val heapDiff = detector.findRepeatedlyGrowingObjects {
      growingList += "growth $iteration"
      iteration++
    }

    val growingObject = heapDiff.growingObjects.single()
    assertThat(growingObject.name).startsWith("INSTANCE_FIELD RepeatingAndroidInProcessScenarioTest.growingList")
  }
}
