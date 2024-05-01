package leakcanary

import leakcanary.TestUtils.assertLeak
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Tests that the [AndroidDetectLeaksAssert] can detect leaks
 * in instrumentation tests
 */
class AndroidDetectLeaksAssertTest {

  @Before fun setUp() {
    AppWatcher.objectWatcher
      .clearAllObjectsTracked()
  }

  @After fun tearDown() {
    AppWatcher.objectWatcher
      .clearAllObjectsTracked()
  }

  @Test fun detectsLeak() {
    leaking = Date()
    val objectWatcher = AppWatcher.objectWatcher
    objectWatcher.expectWeaklyReachable(leaking, "This date should not live beyond the test")
    assertLeak(Date::class.java)
  }

  companion object {
    private lateinit var leaking: Any
  }
}
