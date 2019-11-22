package leakcanary

import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.InternalLeakCanary.FormFactor.TV
import leakcanary.internal.tv.TvOnRetainInstanceListener

/**
 * Default implementation of [OnRetainInstanceListener] that doesn't do anything
 */
class DefaultOnRetainInstanceListener : OnRetainInstanceListener {

  override fun onChange(change: RetainInstanceChange) {}

  companion object {
    fun create(): OnRetainInstanceListener {
      return when (InternalLeakCanary.formFactor){
        TV -> TvOnRetainInstanceListener(InternalLeakCanary.application)
        else -> DefaultOnRetainInstanceListener()
      }
    }
  }
}