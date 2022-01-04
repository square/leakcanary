package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class DetectLeaksAfterTestSuccessTest {

  object CheckAssertNoLeaksInvoked : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
      return object : Statement() {
        override fun evaluate() {
          try {
            var assertNoLeaksInvoked = false
            DetectLeaksAssert.update { tag ->
              assertNoLeaksInvoked = true
            }
            base.evaluate()
            assertThat(assertNoLeaksInvoked).isTrue()
          } finally {
            DetectLeaksAssert.update(AndroidDetectLeaksAssert())
          }
        }
      }
    }
  }

  @get:Rule
  val rule: RuleChain = RuleChain.outerRule(CheckAssertNoLeaksInvoked).around(DetectLeaksAfterTestSuccess())

  @Test fun emptyTest() {
    // This test triggers the rules.
  }
}
