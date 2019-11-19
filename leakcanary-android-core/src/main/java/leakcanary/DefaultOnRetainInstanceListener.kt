package leakcanary

import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.utils.FormFactor.TV
import leakcanary.internal.tv.TvOnRetainInstanceListener
import leakcanary.internal.utils.formFactor

/**
 * Default implementation of [OnRetainInstanceListener] that doesn't do anything
 */
class DefaultOnRetainInstanceListener : OnRetainInstanceListener {

  override fun onChange(change: RetainInstanceChange) {}

  companion object {
    fun create(): OnRetainInstanceListener {
      val application = InternalLeakCanary.application
      return when (application.formFactor){
        TV -> TvOnRetainInstanceListener(application)
        else -> DefaultOnRetainInstanceListener()
      }
    }
  }
}