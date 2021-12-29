package leakcanary

import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.FUNCTION
import leakcanary.SkipLeakDetection.Companion.shouldSkipTest
import org.junit.runner.Description
import shark.SharkLog

/**
 * Annotation for skipping leak detection in a UI test that calls
 * [LeakAssertions.assertNoLeaks]. This annotation is useful to skip a leak detection in
 * a test until the leaks are fixed.
 *
 * The check is performed by [shouldSkipTest] which is called by [AndroidDetectLeaksAssert],
 * which requires that the [TestDescriptionHolder] rule be applied and
 * evaluating when [LeakAssertions.assertNoLeaks] is called.
 *
 * [message] should contain an explanation of why leak detection is skipped, e.g. a reference to a
 * filed issue.
 *
 * The optional [assertionTags] allows finer grained filtering based on the tag value passed to
 * [LeakAssertions.assertNoLeaks]. If [assertionTags] is empty, then the test will
 * skip leak detection entirely. If [assertionTags] is not empty, then the test will skip leak
 * detection for any call to [LeakAssertions.assertNoLeaks] with a tag value contained in
 * [assertionTags].
 */
@Retention(RUNTIME)
@Target(CLASS, FUNCTION)
annotation class SkipLeakDetection(val message: String, vararg val assertionTags: String) {
  companion object {
    fun shouldSkipTest(testDescription: Description, assertionTag: String): Boolean {
      val skipAnnotation =
        testDescription.getAnnotation(SkipLeakDetection::class.java)
      return shouldSkipTest(testDescription.displayName, skipAnnotation, assertionTag)
    }

    fun shouldSkipTest(
      testName: String,
      skipAnnotation: SkipLeakDetection?,
      assertionTag: String
    ): Boolean {
      if (skipAnnotation != null) {
        val assertionTags = skipAnnotation.assertionTags
        if (assertionTags.isEmpty()) {
          SharkLog.d { "Skipping leak detection for $testName, message: ${skipAnnotation.message}" }
          return true
        } else if (assertionTag in assertionTags) {
          SharkLog.d {
            "Skipping [$assertionTag] leak detection for $testName, " +
              "message: ${skipAnnotation.message}"
          }
          return true
        }
      }
      return false
    }
  }
}

