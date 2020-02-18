package leakcanary.internal

import android.app.Instrumentation.REPORT_KEY_IDENTIFIER
import android.os.Bundle
import androidx.test.internal.runner.listener.InstrumentationResultPrinter.REPORT_KEY_NAME_CLASS
import androidx.test.internal.runner.listener.InstrumentationResultPrinter.REPORT_KEY_NAME_TEST
import androidx.test.internal.runner.listener.InstrumentationResultPrinter.REPORT_KEY_STACK
import androidx.test.internal.runner.listener.InstrumentationResultPrinter.REPORT_VALUE_RESULT_FAILURE
import androidx.test.platform.app.InstrumentationRegistry
import leakcanary.FailTestOnLeakRunListener
import org.junit.runner.Description

internal class InstrumentationTestResultPublisher : TestResultPublisher {
  override fun publishTestFinished() {
  }

  override fun publishTestFailure(
    description: Description,
    trace: String
  ) {
    val bundle = Bundle()
    bundle.putString(REPORT_KEY_IDENTIFIER, FailTestOnLeakRunListener::class.java.name)
    bundle.putString(REPORT_KEY_NAME_CLASS, description.className)
    bundle.putString(REPORT_KEY_NAME_TEST, description.methodName)
    bundle.putString(REPORT_KEY_STACK, trace)

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.sendStatus(REPORT_VALUE_RESULT_FAILURE, bundle)
  }
}
