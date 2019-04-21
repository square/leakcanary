package leakcanary.internal

import android.app.Application

interface LeakSentryListener {
  fun onLeakSentryInstalled(application: Application)
  fun onReferenceRetained()

  object None : LeakSentryListener {
    override fun onLeakSentryInstalled(application: Application) {
    }

    override fun onReferenceRetained() {
    }
  }
}