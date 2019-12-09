package leakcanary

import leakcanary.TestUtils.assertLeak
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
    AppWatcher.objectWatcher
        .clearWatchedObjects()
  }

  @After fun tearDown() {
    AppWatcher.objectWatcher
        .clearWatchedObjects()
  }

  @Test fun detectsLeak() {
    leaking = Date()
    val refWatcher = AppWatcher.objectWatcher
    refWatcher.watch(leaking, "This date should not live beyond the test")
    assertLeak(Date::class.java)
  }

  companion object {
    private lateinit var leaking: Any
  }
}
