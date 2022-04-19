package leakcanary

import leakcanary.TestDescriptionHolder.testDescription
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import shark.SharkLog

/**
 * A [TestRule] that holds onto the test [Description] in a thread local while evaluating, making
 * it possible to retrieve that test [Description] from the test thread via [testDescription].
 *
 * This rule is automatically applied by [DetectLeaksAfterTestSuccess].
 */
object TestDescriptionHolder : TestRule {

  private val descriptionThreadLocal = ThreadLocal<Description>()

  fun isEvaluating() = descriptionThreadLocal.get() != null

  val testDescription: Description
    get() {
      return descriptionThreadLocal.get() ?: error(
        "Test description is null, either you forgot to add the TestDescriptionHolder rule around" +
          "the current code or you did not call testDescription from the test thread."
      )
    }

  override fun apply(base: Statement, description: Description): Statement {
    return wrap(base, description)
  }

  fun wrap(base: Statement, description: Description) = object : Statement() {
    override fun evaluate() {
      val previousDescription = descriptionThreadLocal.get()
      val descriptionNotAlreadySet = previousDescription == null
      if (descriptionNotAlreadySet) {
        descriptionThreadLocal.set(description)
      } else {
        SharkLog.d { "Test description already set, you should remove the TestDescriptionHolder rule." }
      }
      try {
        base.evaluate()
      } finally {
        if (descriptionNotAlreadySet) {
          val currentDescription = descriptionThreadLocal.get()
          check(currentDescription != null) {
            "Test description should not be null after the rule evaluates."
          }
          descriptionThreadLocal.remove()
        }
      }
    }
  }
}
