package androidx.test.orchestrator.instrumentationlistener

import androidx.test.internal.events.client.OrchestratedInstrumentationListener
import androidx.test.internal.events.client.TestRunEventService
import androidx.test.services.events.run.TestRunEvent

internal fun OrchestratedInstrumentationListener.delegateSendTestNotification(
  onSendTestNotification: (TestRunEvent, (TestRunEvent) -> Unit) -> Unit
) {
  OrchestratedInstrumentationListener::class.java.getDeclaredField("notificationService")
    .run {
      isAccessible = true
      val notificationService = get(this@delegateSendTestNotification) as TestRunEventService
      val sendTestRunEventCallback: (TestRunEvent) -> Unit = { event ->
        notificationService.send(event)
      }
      set(this@delegateSendTestNotification,
        TestRunEventService { event ->
          onSendTestNotification(event, sendTestRunEventCallback)
        })
    }
}
