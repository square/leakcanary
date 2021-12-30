package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class TestDescriptionHolderTest {

  companion object {
    var beforeClassThrowable: Throwable? = null

    @BeforeClass @JvmStatic fun beforeClass() {
      beforeClassThrowable = try {
        TestDescriptionHolder.testDescription
        null
      } catch (throwable: Throwable) {
        throwable
      }
    }
  }

  class AttemptsRetrievingTestDescription : TestRule {

    var beforeEvaluateThrowable: Throwable? = null

    override fun apply(base: Statement, description: Description): Statement {
      return object : Statement() {
        override fun evaluate() {
          beforeEvaluateThrowable = try {
            TestDescriptionHolder.testDescription
            null
          } catch (throwable: Throwable) {
            throwable
          }
          base.evaluate()
        }
      }
    }
  }

  private val outerRule = AttemptsRetrievingTestDescription()
  private val innerRule = AttemptsRetrievingTestDescription()

  @get:Rule
  val rule = RuleChain.outerRule(outerRule).around(TestDescriptionHolder).around(innerRule)!!

  @Test
  fun retrievingTestDescriptionDoesNotThrowWhileTestEvaluating() {
    TestDescriptionHolder.testDescription
  }

  @Test
  fun currentTestDescriptionIsAccurate() {
    val stackTop = RuntimeException().stackTrace.first()
    val testDescription = TestDescriptionHolder.testDescription
    assertThat(testDescription.className).isEqualTo(stackTop.className)
    assertThat(testDescription.methodName).isEqualTo(stackTop.methodName)
  }

  @Test
  fun testDescriptionThrowsBeforeClass() {
    // On API 16, assertThat(Throwable) causes a TypeComparators class init failure.
    assertThat(beforeClassThrowable == null).isFalse()
  }

  @Test
  fun testDescriptionThrowsBeforeOuterEvaluate() {
    // On API 16, assertThat(Throwable) causes a TypeComparators class init failure.
    assertThat(outerRule.beforeEvaluateThrowable == null).isFalse()
  }

  @Test
  fun testDescriptionDoesNotThrowBeforeInnerEvaluate() {
    // On API 16, assertThat(Throwable) causes a TypeComparators class init failure.
    assertThat(innerRule.beforeEvaluateThrowable == null).isTrue()
  }
}
