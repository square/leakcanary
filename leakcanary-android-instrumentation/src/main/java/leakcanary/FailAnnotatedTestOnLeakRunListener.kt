package leakcanary

import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/**
 * A JUnit [RunListener] extending [FailTestOnLeakRunListener] to detecting memory
 * leaks in Android instrumentation tests only when the [FailTestOnLeak] annotation
 * is used.
 *
 * @see FailTestOnLeak
 */
class FailAnnotatedTestOnLeakRunListener: FailTestOnLeakRunListener() {

  override fun skipLeakDetectionReason(description: Description) =
    if (description.getAnnotation(FailTestOnLeak::class.java) != null) {
      null
    } else {
      "test is not annotated with @FailTestOnLeak"
    }
}