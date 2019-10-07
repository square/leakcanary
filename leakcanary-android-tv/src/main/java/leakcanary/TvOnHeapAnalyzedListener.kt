package leakcanary

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import shark.HeapAnalysis

/**
 * [OnHeapAnalyzedListener] implementation for Android TV devices, which delegates work to
 * [DefaultOnHeapAnalyzedListener] and in addition to that displays a Toast message when leak
 * analysis completes.
 * Android TV devices do not have notifications, therefore the only easy and non-invasive way
 * to communicate with user is via Toast messages. These are used just to grab user attention and
 * to direct them to Logcat where a much more detailed report will be printed.
 */
class TvOnHeapAnalyzedListener(private val application: Application) : OnHeapAnalyzedListener {

  private val handler = Handler(Looper.getMainLooper())
  private val delegate = DefaultOnHeapAnalyzedListener.create()

  override fun onHeapAnalyzed(heapAnalysis: HeapAnalysis) {
    // Post the Toast into main thread and wrap it with try-catch in case Toast crashes (it happens)
    handler.post {
      try {
        Toast.makeText(
            application,
            "Analysis complete, please check Logcat",
            Toast.LENGTH_LONG
        )
            .show()
      } catch (exception: Exception) {
        // Toasts are prone to crashing, ignore
      }
    }
    delegate.onHeapAnalyzed(heapAnalysis)
  }
}