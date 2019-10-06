package leakcanary

import android.app.Application

fun LeakCanary.watchTV(application: Application) {
  config = config.copy(
      onRetainInstanceListener = TvOnRetainInstanceListener(application),
      onHeapAnalyzedListener = TvOnHeapAnalyzedListener(application)
  )
}