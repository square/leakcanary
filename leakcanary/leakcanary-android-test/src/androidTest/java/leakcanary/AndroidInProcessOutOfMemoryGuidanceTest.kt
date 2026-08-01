package leakcanary

import androidx.test.platform.app.InstrumentationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.Test
import shark.HeapDiff

class AndroidInProcessOutOfMemoryGuidanceTest {

  /**
   * The guidance reads the manifest flags of the app under test and of the test apk from a class
   * loaded by name, which only loads when running as an instrumentation test on Android, so nothing
   * but an instrumentation test can tell us that it still does.
   */
  @Test fun out_of_memory_guidance_names_the_app_under_test() {
    val appUnderTestPackageName =
      InstrumentationRegistry.getInstrumentation().targetContext.packageName
    val detector = HeapDiff.repeatingAndroidInProcessScenario(
      heapDumper = { throw OutOfMemoryError("Java heap space") }
    )

    val failure = catchThrowable {
      detector.findRepeatedlyGrowingObjects {
      }
    }

    assertThat(failure.message)
      .startsWith("Not enough memory to detect heap growth: this process can use up to ")
      .contains("in the manifest of the app under test ($appUnderTestPackageName)")
  }
}
