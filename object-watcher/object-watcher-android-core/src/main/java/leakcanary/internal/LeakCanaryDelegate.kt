package leakcanary.internal

import android.app.Application
import leakcanary.OnObjectRetainedListener

internal object LeakCanaryDelegate {

  @Suppress("UNCHECKED_CAST")
  val loadLeakCanary by lazy {
    try {
      val leakCanaryListener = Class.forName("leakcanary.internal.InternalLeakCanary")
      leakCanaryListener.getDeclaredField("INSTANCE")
        .get(null) as (Application) -> Unit
    } catch (ignored: Throwable) {
      NoLeakCanary
    }
  }

  object NoLeakCanary : (Application) -> Unit, OnObjectRetainedListener {
    override fun invoke(application: Application) {
    }

    override fun onObjectRetained() {
    }
  }
}