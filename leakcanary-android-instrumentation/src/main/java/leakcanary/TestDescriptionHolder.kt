package leakcanary

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A [TestRule] that holds onto the test [Description] in a thread local while evaluating, making
 * it possible to retrieve that test [Description] from the test thread via [testDescription].
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
    return object : Statement() {
      override fun evaluate() {
        try {
          val previousDescription = descriptionThreadLocal.get()
          check(previousDescription == null) {
            "Test description should be null not [$previousDescription] before the rule evaluates. " +
              "Did you add the TestDescriptionHolder rule twice by mistake?"
          }
          descriptionThreadLocal.set(description)
          base.evaluate()
        } finally {
          val currentDescription = descriptionThreadLocal.get()
          check(currentDescription != null) {
            "Test description should not be null after the rule evaluates. " +
              "Did you add the TestDescriptionHolder rule twice by mistake?"
          }
          descriptionThreadLocal.remove()
        }
      }
    }
  }
}
