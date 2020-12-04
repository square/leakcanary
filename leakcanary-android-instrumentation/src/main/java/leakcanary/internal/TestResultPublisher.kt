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
        try {
          AndroidJUnitRunner::class.java.getDeclaredField("orchestratorListener")
            .run {
              isAccessible = true
              get(instrumentation) as OrchestratedInstrumentationListener?
            }
        } catch (e: NoSuchFieldException) {
          // Starting from `android-test 1.3.1-alpha02`, test failure aren't properly reported
          // due to missing `orchestratorListener` field. Avoid failure for now.
          // See https://github.com/square/leakcanary/issues/1986
          null
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
