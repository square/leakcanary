package leakcanary.internal

import androidx.test.internal.events.client.OrchestratedInstrumentationListener
import androidx.test.internal.events.client.TestEventClient
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import org.junit.runner.Description
import shark.SharkLog

internal interface TestResultPublisher {

  fun publishTestFinished()

  fun publishTestFailure(
    description: Description,
    exception: Throwable
  )

  companion object {
    fun install(): TestResultPublisher {
      val orchestratorListener = getOrchestratorListener()
      return if (orchestratorListener != null) {
        SharkLog.d { "Android Test Orchestrator detected, failures will be sent via binder callback" }
        OrchestratorTestResultPublisher(orchestratorListener)
      } else {
        SharkLog.d { "Failures will be sent via Instrumentation.sendStatus()" }
        InstrumentationTestResultPublisher()
      }
    }
  }
}

private fun getOrchestratorListener(): OrchestratedInstrumentationListener? {
  return (InstrumentationRegistry.getInstrumentation() as? AndroidJUnitRunner)?.let { instrumentation->
      try {
        AndroidJUnitRunner::class.java.getDeclaredField("testEventClient")
          .run {
            isAccessible = true
            (get(instrumentation) as TestEventClient?)?.runListener as? OrchestratedInstrumentationListener?
          }
      } catch (e: NoSuchFieldException) {
        null
      }
    }
}
