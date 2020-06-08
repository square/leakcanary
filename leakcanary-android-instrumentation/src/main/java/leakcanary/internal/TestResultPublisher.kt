package leakcanary.internal

import androidx.test.orchestrator.instrumentationlistener.OrchestratedInstrumentationListener
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import org.junit.runner.Description
import shark.SharkLog

internal interface TestResultPublisher {

  fun publishTestFinished()

  fun publishTestFailure(
    description: Description,
    trace: String
  )

  companion object {
    fun install(): TestResultPublisher {
      val instrumentation = InstrumentationRegistry.getInstrumentation()
      val orchestratorListener = if (instrumentation is AndroidJUnitRunner) {
        AndroidJUnitRunner::class.java.getDeclaredField("orchestratorListener")
            .run {
              isAccessible = true
              get(instrumentation) as OrchestratedInstrumentationListener?
            }
      } else null
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
