package leakcanary

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class TvOnRetainInstanceListener(private val application: Application) : OnRetainInstanceListener {
  private var lastRetainedCount = 0
  private val handler = Handler(Looper.getMainLooper())

  override fun onCountChanged(retainedCount: Int) {
    if (retainedCount == lastRetainedCount) return
    lastRetainedCount = retainedCount

    val retainedVisibleThreshold = LeakCanary.config.retainedVisibleThreshold
    val text = "Retained objects: $retainedCount." +
        if (retainedCount >= retainedVisibleThreshold) " Dumping the heap..."
        else "Heap dump threshold is $retainedVisibleThreshold"
    handler.post {
      try {
        Toast.makeText(application, text, Toast.LENGTH_LONG).show()
      } catch (exception: Exception) {
        //Toasts are prone to crashing
      }
    }
  }
}