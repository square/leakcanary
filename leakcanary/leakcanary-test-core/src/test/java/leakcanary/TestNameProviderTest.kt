package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

class TestNameProviderTest {

  @get:Rule val optionallyAddTestDescriptionHolderRule = OptionallyAddTestDescriptionHolderRule()

  @Test
  fun `provides class name`() {
    check(!TestDescriptionHolder.isEvaluating())

    val testName = TestNameProvider.currentTestName()

    assertThat(testName!!.classSimpleName).isEqualTo("TestNameProviderTest")
  }

  @ApplyTestDescriptionHolderRule
  @Test
  fun `provides class name through test description holder`() {
    check(TestDescriptionHolder.isEvaluating())

    val testName = TestNameProvider.currentTestName()

    assertThat(testName!!.classSimpleName).isEqualTo("TestNameProviderTest")
  }

  @Test
  fun `provides method name`() {
    val testName = TestNameProvider.currentTestName()

    assertThat(testName!!.methodName).isEqualTo("provides method name")
  }
}

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class ApplyTestDescriptionHolderRule

class OptionallyAddTestDescriptionHolderRule : TestRule {
  override fun apply(
    base: Statement,
    description: Description
  ): Statement {
    return if (description.getAnnotation(ApplyTestDescriptionHolderRule::class.java) != null) {
      TestDescriptionHolder.apply(base, description)
    } else {
      base
    }
  }
}

@RunWith(Enclosed::class)
class EnclosingTestNameProviderTest {

  class InnerTest {

    @get:Rule val optionallyAddTestDescriptionHolderRule = OptionallyAddTestDescriptionHolderRule()

    @Test
    fun `provides class name`() {
      check(!TestDescriptionHolder.isEvaluating())

      val testName = TestNameProvider.currentTestName()

      assertThat(testName!!.classSimpleName).isEqualTo("InnerTest")
    }

    @ApplyTestDescriptionHolderRule
    @Test
    fun `provides class name through test description holder`() {
      check(TestDescriptionHolder.isEvaluating())

      val testName = TestNameProvider.currentTestName()

      assertThat(testName!!.classSimpleName).isEqualTo("InnerTest")
    }
  }
}
