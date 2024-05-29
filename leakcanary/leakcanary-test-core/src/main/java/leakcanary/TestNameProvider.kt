package leakcanary

import org.junit.runner.Description

object TestNameProvider {

  fun currentTestName(): TestName? {
    return if (TestDescriptionHolder.isEvaluating()) {
      val description = TestDescriptionHolder.testDescription
      TestName.FromTestDescription(description)
    } else {
      val currentStack = Thread.currentThread().stackTrace.toList()
      val junitIndex = currentStack.indexOfFirst { it.className.startsWith("org.junit") }
      if (junitIndex > 0) {
        val aboveJunit = currentStack.subList(0, junitIndex)
        var testMethodIndex = aboveJunit.lastIndex
        while (testMethodIndex >= 0 && aboveJunit[testMethodIndex].className.run {
            startsWith(
              "jdk."
            ) || startsWith("java.")
          }) {
          testMethodIndex--
        }
        if (testMethodIndex < 1) {
          null
        } else {
          val testStackElement = aboveJunit[testMethodIndex]
          TestName.FromStackTraceElement(testStackElement)
        }
      } else {
        null
      }
    }
  }
}

sealed interface TestName {
  val className: String
  val classSimpleName: String
  val methodName: String

  class FromTestDescription(private val testDescription: Description) : TestName {
    override val className: String get() = testDescription.className
    override val classSimpleName: String get() = testDescription.testClass.simpleName
    override val methodName: String get() = testDescription.methodName
  }

  class FromStackTraceElement(private val testStackElement: StackTraceElement) : TestName {
    override val className: String get() = testStackElement.className
    override val classSimpleName: String
      get() = testStackElement.className
        .substringAfterLast(".")
        .substringAfterLast("$")
    override val methodName: String get() = testStackElement.methodName
  }
}
