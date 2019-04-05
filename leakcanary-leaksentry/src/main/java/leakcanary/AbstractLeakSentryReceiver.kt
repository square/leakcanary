package leakcanary

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import leakcanary.internal.InternalLeakSentry

abstract class AbstractLeakSentryReceiver : BroadcastReceiver() {

  final override fun onReceive(
    context: Context,
      // Nullable because Android can never be trusted.
    intent: Intent?
  ) {
    when (intent?.action) {
      LEAK_SENTRY_INSTALLED_INTENT_ACTION -> onLeakSentryInstalled(
          InternalLeakSentry.application
      )
      REFERENCE_RETAINED_INTENT_ACTION -> onReferenceRetained()
    }
  }

  abstract fun onLeakSentryInstalled(application: Application)

  abstract fun onReferenceRetained()

  companion object {

    const val REFERENCE_RETAINED_INTENT_ACTION =
      "leakcanary.AbstractLeakSentryReceiver.referenceRetained"
    const val LEAK_SENTRY_INSTALLED_INTENT_ACTION =
      "leakcanary.AbstractLeakSentryReceiver.leakSentryInstalled"

    internal fun sendLeakSentryInstalled() {
      sendPrivateBroadcast(
          LEAK_SENTRY_INSTALLED_INTENT_ACTION
      )
    }

    internal fun sendReferenceRetained() {
      sendPrivateBroadcast(
          REFERENCE_RETAINED_INTENT_ACTION
      )
    }

    private fun sendPrivateBroadcast(action: String) {
      val intent = Intent(action)
      intent.setPackage(InternalLeakSentry.application.packageName)
      InternalLeakSentry.application.sendBroadcast(intent)
    }
  }
}