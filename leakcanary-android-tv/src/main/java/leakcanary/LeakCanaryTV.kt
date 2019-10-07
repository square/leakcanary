@file:JvmName("LeakCanaryTV")

package leakcanary

import android.app.Application

/**
 * Helper class that configures LeakCanary to work better on Android TV devices
 * It will add listeners that display Toast messages fo better communication with users since
 * Android TV devices do not have notifications.
 * This methods should be called from the *debug* [Application] instance's [Application.onCreate]
 * method.
 *
 * Kotlin: `LeakCanary.watchTV(application)`
 *
 * Java: `LeakCanaryTV.watchTV(LeakCanary.INSTANCE, application)`
 */
fun LeakCanary.watchTV(application: Application) {
  config = config.copy(
      onRetainInstanceListener = TvOnRetainInstanceListener(application),
      onHeapAnalyzedListener = TvOnHeapAnalyzedListener(application)
  )
}