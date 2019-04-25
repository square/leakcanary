package leakcanary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.Description

/**
 * Tests that the [FailTestOnLeakRunListener] can detect (or not) leaks
 * in instrumentation tests when the correct annotation is used
 */
class FailTestOnLeakRunListenerTest {

  @LeakTest
  @Test
  fun detectsLeak() {
    val annotation =
      javaClass.getMethod("detectsLeak").getAnnotation(LeakTest::class.java)
    val description = Description.createTestDescription("test", "Test mechanism", annotation)
    val listener = FailTestOnLeakRunListener()
    assertNull(listener.skipLeakDetectionReason(description))
  }

  @Test
  fun skipsLeakDetectionWithoutAnnotation() {
    val description = Description.createTestDescription("test", "Test mechanism")
    val listener = FailTestOnLeakRunListener()
    assertEquals("skipped leak detection", listener.skipLeakDetectionReason(description))
  }
}



