package leakcanary

import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * [TestRule] that invokes [LeakAssertions.assertNoLeaks] after the test
 * successfully evaluates. Pay attention to where you set up this rule in the
 * rule chain as you might detect different leaks (e.g. around vs wrapped by the
 * activity rule). It's also possible to use this rule several times in a rule
 * chain.
 *
 * This rule automatically applies the [TestDescriptionHolder] rule.
 */
class DetectLeaksAfterTestSuccess(
  private val tag: String = DetectLeaksAfterTestSuccess::class.java.simpleName
) : TestRule {
  override fun apply(base: Statement, description: Description): Statement {
    return TestDescriptionHolder.wrap(object : Statement() {
      override fun evaluate() {
        try {
          base.evaluate()
          // If the test fails, evaluate() will throw and we won't run the analysis (which is good).
          LeakAssertions.assertNoLeaks(tag)
        } finally {
          // Otherwise upstream test failures will be reported as leaks.
          // https://github.com/square/leakcanary/issues/2297
          AppWatcher.objectWatcher.clearAllObjectsTracked()
        }
      }
    }, description)
  }

  companion object {
    /**
     * A helper function to trigger leak detection twice during test tear down, before and after
     * the tear down of a set of wrapped rule chains. For example, this can be useful to detect
     * leaks both right before and right after the activity under test is destroyed. Before means
     * we can detect detached fragment leaks that go away when the activity is destroyed. After
     * means we can detect activity leaks.
     *
     * ```kotlin
     * RuleChain.outerRule(LoginRule())
     *   .detectLeaksAfterTestSuccessWrapping("ActivitiesDestroyed") {
     *     around(ActivityScenarioRule(MyActivity::class.java))
     *   }
     *   .around(LoadingScreenRule())
     * ```
     */
    fun RuleChain.detectLeaksAfterTestSuccessWrapping(
      tag: String,
      wrapped: RuleChain.() -> RuleChain
    ): RuleChain {
      return around(DetectLeaksAfterTestSuccess("After$tag")).wrapped()
        .around(DetectLeaksAfterTestSuccess("Before$tag"))
    }
  }
}


