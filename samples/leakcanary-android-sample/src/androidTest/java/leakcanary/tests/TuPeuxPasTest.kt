package leakcanary.tests

import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.example.leakcanary.MainActivity
import leakcanary.DetectLeaksAfterTestSuccess
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * This UI test looks like it should succeed, but it will actually fail because
 * it triggers a leak.
 *
 * Run this test with:
 *
 * ./gradlew leakcanary-android-sample:connectedCheck
 *
 * Why is this class named "TuPeuxPasTest"?
 *
 * This test fails, intentionally. In French, "Tu peux pas test" could mean "you cannot test"
 * written with poor grammar. Except, that's not what it means.
 * If you're curious, interested in French and have time to waste:
 * https://www.youtube.com/watch?v=DZZpbmAc-0A
 * https://www.youtube.com/watch?v=nHeAA6X-XUQ
 */
class TuPeuxPasTest {

  private val activityRule = ActivityScenarioRule(MainActivity::class.java)

  @get:Rule
  val rules = RuleChain.outerRule(DetectLeaksAfterTestSuccess()).around(activityRule)!!

  @Test
  fun activityLeakingAfterTest() {
    activityRule.scenario.onActivity { activity ->
      leakedObjects += activity
    }
  }

  companion object {
    val leakedObjects = mutableListOf<Any>()
  }
}
