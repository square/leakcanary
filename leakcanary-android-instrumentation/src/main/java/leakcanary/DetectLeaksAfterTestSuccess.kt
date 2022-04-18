package leakcanary

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
          AppWatcher.objectWatcher.clearWatchedObjects()
        }
      }
    }, description)
  }
}


