package androidx.test.orchestrator.instrumentationlistener

import android.os.Bundle
import androidx.test.orchestrator.callback.OrchestratorCallback

internal fun OrchestratedInstrumentationListener.delegateSendTestNotification(
  onSendTestNotification: (Bundle, (Bundle) -> Unit) -> Unit
) {
  val realCallback = odoCallback

  val sendTestNotificationCallback: (Bundle) -> Unit = { bundle ->
    realCallback.sendTestNotification(bundle)
  }

  odoCallback = object : OrchestratorCallback by realCallback {
    override fun sendTestNotification(bundle: Bundle) {
      onSendTestNotification(bundle, sendTestNotificationCallback)
    }
  }
}
