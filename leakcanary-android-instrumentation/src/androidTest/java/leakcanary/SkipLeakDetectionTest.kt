package leakcanary

import leakcanary.LeakAssertions.NO_TAG
import leakcanary.SkipLeakDetection.Companion.shouldSkipTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test

class SkipLeakDetectionTest {

  @get:Rule
  val rule = TestDescriptionHolder

  @SkipLeakDetection("")
  @Test fun testAnnotatedSkipsLeakDetection() {
    assertThat(shouldSkipTest(TestDescriptionHolder.testDescription, NO_TAG)).isTrue()
  }

  @Test fun testNotAnnotatedDoesNotSkipLeakDetection() {
    assertThat(shouldSkipTest(TestDescriptionHolder.testDescription, NO_TAG)).isFalse()
  }

  @SkipLeakDetection("")
  @Test fun testAnnotatedSkipsLeakDetectionForTag() {
    assertThat(shouldSkipTest(TestDescriptionHolder.testDescription, "tag")).isTrue()
  }

  @SkipLeakDetection("", "tag")
  @Test fun testAnnotatedSkipsLeakDetectionForTargetTag() {
    assertThat(shouldSkipTest(TestDescriptionHolder.testDescription, "tag")).isTrue()
  }

  @SkipLeakDetection("", "tag1", "tag2")
  @Test fun testAnnotatedDoesNotSkipLeakDetectionForUnlistedTag() {
    assertThat(shouldSkipTest(TestDescriptionHolder.testDescription, "tag")).isFalse()
  }

  @SkipLeakDetection("", "tag1", "tag2")
  @Test fun testAnnotatedSkipsLeakDetectionForListedTag() {
    assertThat(shouldSkipTest(TestDescriptionHolder.testDescription, "tag2")).isTrue()
  }
}
