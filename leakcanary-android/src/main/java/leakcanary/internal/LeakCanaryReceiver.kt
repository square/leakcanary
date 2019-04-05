package leakcanary.internal

import android.app.Application
import leakcanary.AbstractLeakSentryReceiver

internal class LeakCanaryReceiver : AbstractLeakSentryReceiver() {
  override fun onLeakSentryInstalled(application: Application) {
    InternalLeakCanary.onLeakSentryInstalled(application)
  }

  override fun onReferenceRetained() {
    InternalLeakCanary.onReferenceRetained()
  }
}