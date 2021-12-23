package leakcanary

import android.app.Application
import shark.HeapAnalysis

/**
 * Deprecated, this is now a no-op. Add to LeakCanary.config.eventListeners instead.
 *
 * Default [OnHeapAnalyzedListener] implementation, which will store the analysis to disk and
 * show a notification summarizing the result.
 */
@Deprecated(message = "Add to LeakCanary.config.eventListeners instead")
class DefaultOnHeapAnalyzedListener private constructor() :
  OnHeapAnalyzedListener {

  // Kept this constructor for backward compatibility of public API.
  @Deprecated(message = "Add to LeakCanary.config.eventListeners instead")
  constructor(application: Application) : this()

  override fun onHeapAnalyzed(heapAnalysis: HeapAnalysis) {
  }

  companion object {
    fun create(): OnHeapAnalyzedListener = DefaultOnHeapAnalyzedListener()
  }
}
