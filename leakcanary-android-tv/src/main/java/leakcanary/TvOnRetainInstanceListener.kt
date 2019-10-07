package leakcanary

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * [OnRetainInstanceListener] implementation for Android TV devices which keeps track of the current
 * retained instances count and displays a helpful Toast message on current state of it.
 * It will notify user when heap dump is going to happen, so that users who set the
 * [LeakCanary.Config.retainedVisibleThreshold] to any value other than 1 will be aware of retain
 * instances changes. If user set threshold for heap dump to 1 then this listener becomes no-op,
 * as any retain instance change will trigger the heap dump with its own toast.
 *
 */
class TvOnRetainInstanceListener(private val application: Application) : OnRetainInstanceListener {

  private var lastRetainedCount = 0
  private val handler = Handler(Looper.getMainLooper())

  override fun onCountChanged(retainedCount: Int) {
    // Do nothing if count hasn't changed
    if (retainedCount == lastRetainedCount) return

    // Remember new retained count
    lastRetainedCount = retainedCount

    // Construct message: either about dumping a heap or on when it will happen
    val retainedVisibleThreshold = LeakCanary.config.retainedVisibleThreshold

    // Don't display any toast if single retain instance triggers a heap dump
    if (retainedVisibleThreshold == 1) return

    var text = "Retained objects: $retainedCount."
    text +=
      if (retainedCount >= retainedVisibleThreshold)
        " Dumping the heap..."
      else
        "Heap dump threshold is $retainedVisibleThreshold" +
            "\nBackground the app to trigger heap dump immediately"

    // Post the Toast into main thread and wrap it with try-catch in case Toast crashes (it happens)
    handler.post {
      try {
        Toast.makeText(application, text, Toast.LENGTH_LONG).show()
      } catch (exception: Exception) {
        // Toasts are prone to crashing, ignore
      }
    }
  }

  override fun onReset() {
    lastRetainedCount = 0
  }
}