package leakcanary.internal

import android.os.Bundle
import androidx.test.orchestrator.instrumentationlistener.OrchestratedInstrumentationListener
import androidx.test.orchestrator.instrumentationlistener.delegateSendTestNotification
import androidx.test.orchestrator.junit.ParcelableDescription
import androidx.test.orchestrator.junit.ParcelableFailure
import androidx.test.orchestrator.junit.ParcelableResult
import androidx.test.orchestrator.listeners.OrchestrationListenerManager.KEY_TEST_EVENT
import androidx.test.orchestrator.listeners.OrchestrationListenerManager.TestEvent.TEST_FAILURE
import androidx.test.orchestrator.listeners.OrchestrationListenerManager.TestEvent.TEST_FINISHED
import androidx.test.orchestrator.listeners.OrchestrationListenerManager.TestEvent.TEST_RUN_FINISHED
import org.junit.runner.Description

internal class OrchestratorTestResultPublisher(listener: OrchestratedInstrumentationListener) :
    TestResultPublisher {

  private var sendTestFinished: (() -> Unit)? = null

  private var failureBundle: Bundle? = null

  private var receivedTestFinished: Boolean = false

  init {
    val failures = mutableListOf<ParcelableFailure>()
    listener.delegateSendTestNotification { testEventBundle, sendTestNotification ->

      when (testEventBundle.getString(KEY_TEST_EVENT)) {
        TEST_FINISHED.toString() -> {
          sendTestFinished = {
            failureBundle?.let { failureBundle ->
              failures += failureBundle.get("failure") as ParcelableFailure
              sendTestNotification(failureBundle)
            }
            sendTestNotification(testEventBundle)
            // reset for next test if any.
            sendTestFinished = null
            failureBundle = null
            receivedTestFinished = false
          }
          if (receivedTestFinished) {
            sendTestFinished!!.invoke()
          }
        }
        TEST_RUN_FINISHED.toString() -> {
          if (failures.isNotEmpty()) {
            val result = testEventBundle.get("result") as ParcelableResult
            result.failures += failures
          }
          sendTestNotification(testEventBundle)
        }
        else -> sendTestNotification(testEventBundle)
      }
    }
  }

  override fun publishTestFinished() {
    receivedTestFinished = true
    sendTestFinished?.invoke()
  }

  override fun publishTestFailure(
    description: Description,
    trace: String
  ) {
    val result = Bundle()
    val failure = ParcelableFailure(
        ParcelableDescription(description),
        RuntimeException(trace)
    )
    result.putParcelable("failure", failure)
    result.putString(KEY_TEST_EVENT, TEST_FAILURE.toString())
    this.failureBundle = result
  }
}
