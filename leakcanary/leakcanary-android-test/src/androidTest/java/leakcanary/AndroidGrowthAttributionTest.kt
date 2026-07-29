package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HeapDiff

class AndroidGrowthAttributionTest {

  private val growingList = mutableListOf<String>()

  @Test fun growth_is_attributed_to_the_code_that_grew_the_list() {
    var iteration = 0
    val scenario: () -> Unit = {
      growingList += "growth ${iteration++}"
    }
    val heapDiff = HeapDiff.repeatingAndroidInProcessScenario()
      .findRepeatedlyGrowingObjects(roundTripScenario = scenario)

    val attributions = ObjectGrowthAttributor(fieldOwners = listOf(this))
      .attributeGrowth(heapDiff, roundTripScenario = scenario)

    val attributed = attributions.single() as GrowthAttribution.Attributed
    val growthStack = attributed.growthStacks.single()
    assertThat(growthStack.methodName).isEqualTo("add")
    assertThat(growthStack.count).isEqualTo(1)
    assertThat(growthStack.stackTrace[0].className)
      .startsWith(AndroidGrowthAttributionTest::class.java.name)
  }
}
