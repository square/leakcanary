package leakcanary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.Description

/**
 * Tests that the [FailAnnotatedTestOnLeakRunListenerTest] only detect leaks
 * in instrumentation tests when the correct annotation is used
 */
class FailAnnotatedTestOnLeakRunListenerTest {

  @FailTestOnLeak
  @Test
  fun detectsLeak() {
    val annotation =
      javaClass.getMethod("detectsLeak").getAnnotation(FailTestOnLeak::class.java)
    val description = Description.createTestDescription("test", "Test mechanism", annotation)
    val listener = FailAnnotatedTestOnLeakRunListener()
    val method =
      listener.javaClass.getDeclaredMethod("skipLeakDetectionReason", Description::class.java)
    method.isAccessible = true
    val result = method.invoke(listener, description)
    assertNull(result)
  }

  @Test
  fun skipsLeakDetectionWithoutAnnotation() {
    val description = Description.createTestDescription("test", "Test mechanism")
    val listener = FailAnnotatedTestOnLeakRunListener()
    val method =
      listener.javaClass.getDeclaredMethod("skipLeakDetectionReason", Description::class.java)
    method.isAccessible = true
    val result = method.invoke(listener, description)
    assertEquals("test is not annotated with @FailTestOnLeak", result)
  }
}