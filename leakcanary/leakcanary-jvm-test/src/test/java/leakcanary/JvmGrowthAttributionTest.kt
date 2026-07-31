package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HeapDiff
import shark.ObjectGrowthDetector
import shark.forJvmHeap

class JvmGrowthAttributionTest {

  /** Declared as a [MutableList], so the field type is the `java.util.List` interface. */
  val leakies = mutableListOf<Any>()

  /** Declared as a [HashMap], so the field type is a concrete class. */
  val leakyHashMap = HashMap<String, Any>()

  @Test
  fun `growth of a list is attributed to the code that added to it`() {
    val scenario = { leakies += Any() }
    val heapDiff = findRepeatedlyGrowingObjects(scenario)

    val attributions = ObjectGrowthAttributor(fieldOwners = listOf(this))
      .attributeGrowth(heapDiff, roundTripScenario = scenario)

    val attributed = attributions.single() as GrowthAttribution.Attributed
    val growthStack = attributed.growthStacks.single()
    assertThat(growthStack.methodName).isEqualTo("add")
    assertThat(growthStack.count).isEqualTo(1)
    assertThat(growthStack.stackTrace.first().className)
      .startsWith(JvmGrowthAttributionTest::class.java.name)
  }

  @Test
  fun `growth is counted once per scenario loop`() {
    val scenarioLoops = 3
    val scenario = { leakies += Any() }
    val heapDiff = findRepeatedlyGrowingObjects(scenario)

    val attributions = ObjectGrowthAttributor(fieldOwners = listOf(this))
      .attributeGrowth(heapDiff, scenarioLoops = scenarioLoops, roundTripScenario = scenario)

    val attributed = attributions.single() as GrowthAttribution.Attributed
    assertThat(attributed.growthStacks.single().count).isEqualTo(scenarioLoops)
  }

  @Test
  fun `the original collection is restored and keeps every added object`() {
    val scenario = { leakies += Any() }
    val heapDiff = findRepeatedlyGrowingObjects(scenario)
    val sizeBeforeAttribution = leakies.size

    ObjectGrowthAttributor(fieldOwners = listOf(this))
      .attributeGrowth(heapDiff, scenarioLoops = 2, roundTripScenario = scenario)

    assertThat(leakies).isNotInstanceOf(java.lang.reflect.Proxy::class.java)
    assertThat(leakies).hasSize(sizeBeforeAttribution + 2)
  }

  @Test
  fun `growth of a field declared as a concrete type is not attributed`() {
    var index = 0
    val scenario = { leakyHashMap["key${++index}"] = Any() }
    val heapDiff = findRepeatedlyGrowingObjects(scenario)

    val attributions = ObjectGrowthAttributor(fieldOwners = listOf(this))
      .attributeGrowth(heapDiff, roundTripScenario = scenario)

    val notAttributed = attributions.single() as GrowthAttribution.NotAttributed
    assertThat(notAttributed.reason).contains("java.util.HashMap", "isn't an interface")
  }

  @Test
  fun `growth of an instance field is not attributed when its owner isn't passed in`() {
    val scenario = { leakies += Any() }
    val heapDiff = findRepeatedlyGrowingObjects(scenario)

    val attributions = ObjectGrowthAttributor().attributeGrowth(
      heapDiff = heapDiff,
      roundTripScenario = scenario
    )

    val notAttributed = attributions.single() as GrowthAttribution.NotAttributed
    assertThat(notAttributed.reason).contains("fieldOwners")
  }

  private fun findRepeatedlyGrowingObjects(scenario: () -> Unit): HeapDiff {
    val detector = HeapDiff.repeatingJvmInProcessScenario(
      objectGrowthDetector = ObjectGrowthDetector.forJvmHeap(),
    )
    val heapDiff = detector.findRepeatedlyGrowingObjects(
      scenarioLoopsPerDump = 1,
      roundTripScenario = scenario
    )
    check(heapDiff.isGrowing) {
      "Expected the scenario to grow the heap, got $heapDiff"
    }
    return heapDiff
  }
}
