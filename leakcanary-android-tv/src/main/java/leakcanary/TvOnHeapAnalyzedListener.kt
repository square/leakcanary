package leakcanary

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import shark.HeapAnalysis

/**
 * Default [OnHeapAnalyzedListener] implementation, which will store the analysis to disk and
 * show a notification summarizing the result.
 */
class TvOnHeapAnalyzedListener(private val application: Application) : OnHeapAnalyzedListener {
  private val handler = Handler(Looper.getMainLooper())
  private val delegate = DefaultOnHeapAnalyzedListener.create()

  override fun onHeapAnalyzed(heapAnalysis: HeapAnalysis) {
    //SharkLog.d { "Launch intent: $pendingIntent" }
    handler.post {
      try {
        Toast.makeText(application, "Analysis complete, please check log", Toast.LENGTH_LONG).show()
      } catch (exception: Exception) {
        //Toasts are prone to crashing
      }
    }
    delegate.onHeapAnalyzed(heapAnalysis)
  }
}