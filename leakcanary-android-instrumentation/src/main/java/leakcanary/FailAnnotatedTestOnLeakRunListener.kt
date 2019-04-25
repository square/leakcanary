package leakcanary

import org.junit.runner.Description

class FailAnnotatedTestOnLeakRunListener: FailTestOnLeakRunListener() {

  override fun skipLeakDetectionReason(description: Description): String? {
    return if (description.getAnnotation(FailTestOnLeak::class.java) != null)
          null
      else
          "skipped leak detection"
  }
}