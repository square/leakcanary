package leakcanary

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Tests that the [InstrumentationLeakDetector] can detect leaks
 * in instrumentation tests
 */
class InstrumentationLeakDetectorTest {

  @Before fun setUp() {
    LeakSentry.refWatcher
        .clearWatchedReferences()
  }

  @After fun tearDown() {
    LeakSentry.refWatcher
        .clearWatchedReferences()
  }

  @Test fun detectsLeak() {
    leaking = Date()
    val refWatcher = LeakSentry.refWatcher
    refWatcher.watch(leaking)

    val leakDetector = InstrumentationLeakDetector()
    val results = leakDetector.detectLeaks()

    if (results.detectedLeaks.size != 1) {
      throw AssertionError("Expected exactly one leak, not ${results.detectedLeaks.size}")
    }

    val firstResult = results.detectedLeaks[0]

    val leakingClassName = firstResult.analysisResult.className

    if (leakingClassName != Date::class.java.name) {
      throw AssertionError("Expected a leak of Date, not $leakingClassName")
    }
  }

  companion object {
    private lateinit var leaking: Any
  }
}
