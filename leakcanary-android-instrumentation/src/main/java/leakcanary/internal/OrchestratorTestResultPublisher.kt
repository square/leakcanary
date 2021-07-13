package leakcanary.internal

import androidx.test.internal.events.client.OrchestratedInstrumentationListener
import androidx.test.orchestrator.instrumentationlistener.delegateSendTestNotification
import androidx.test.services.events.FailureInfo
import androidx.test.services.events.ParcelableConverter.getTestCaseFromDescription
import androidx.test.services.events.run.TestFailureEvent
import androidx.test.services.events.run.TestFinishedEvent
import androidx.test.services.events.run.TestRunFinishedEvent
import org.junit.runner.Description
import org.junit.runner.notification.Failure

internal class OrchestratorTestResultPublisher(listener: OrchestratedInstrumentationListener) :
  TestResultPublisher {

  private var sendTestFinished: (() -> Unit)? = null

  private var failureInfo: FailureInfo? = null

  private var receivedTestFinished: Boolean = false

  init {
    val failures = mutableListOf<FailureInfo>()

    listener.delegateSendTestNotification { testEvent, sendTestRunEvent ->
      when(testEvent) {
        is TestFinishedEvent -> {
          sendTestFinished = {
            failureInfo?.let { failureInfo ->
              failures += failureInfo
              sendTestRunEvent(TestFailureEvent(
                testEvent.testCase,
                failureInfo
              ))
            }
            sendTestRunEvent(testEvent)
            // reset for next test if any.
            sendTestFinished = null
            failureInfo = null
            receivedTestFinished = false
          }
          if (receivedTestFinished) {
            sendTestFinished!!.invoke()
          }
        }
        is TestRunFinishedEvent -> {
          if (failures.isNotEmpty()) {
            testEvent.failures += failures
          }
          sendTestRunEvent(testEvent)
        }
        else -> {
          sendTestRunEvent(testEvent)
        }
      }
    }
  }

  override fun publishTestFinished() {
    receivedTestFinished = true
    sendTestFinished?.invoke()
  }

  override fun publishTestFailure(
    description: Description,
    exception: Throwable
  ) {
    val testCase = getTestCaseFromDescription(description)

    val failure = Failure(description, exception)

    this.failureInfo = FailureInfo(
      failure.message, failure.testHeader, failure.trace, testCase)
  }
}
