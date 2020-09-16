package leakcanary.internal.tv

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.squareup.leakcanary.core.R
import leakcanary.LeakCanary
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.OnRetainInstanceListener
import leakcanary.internal.RetainInstanceEvent
import leakcanary.internal.RetainInstanceEvent.CountChanged.BelowThreshold
import leakcanary.internal.RetainInstanceEvent.CountChanged.DumpHappenedRecently
import leakcanary.internal.RetainInstanceEvent.CountChanged.DumpingDisabled
import leakcanary.internal.RetainInstanceEvent.NoMoreObjects
import shark.SharkLog

/**
 * [OnRetainInstanceListener] implementation for Android TV devices which displays a helpful
 * Toast message on current state of retained instances.
 * It will notify user when heap dump is going to happen, so that users who set the
 * [LeakCanary.Config.retainedVisibleThreshold] to any value other than 1 will be aware of retain
 * instances changes. Additionally, it notifies about debugger being attached and whether heap dump
 * happened recently.
 */
internal class TvOnRetainInstanceListener(private val application: Application) :
    OnRetainInstanceListener {

  private val handler = Handler(Looper.getMainLooper())

  override fun onEvent(event: RetainInstanceEvent) {
    val message = when (event) {
      NoMoreObjects -> {
        application.getString(R.string.leak_canary_notification_no_retained_object_title)
      }
      is BelowThreshold -> {
        application.getString(
            R.string.leak_canary_tv_toast_retained_objects,
            event.retainedCount,
            LeakCanary.config.retainedVisibleThreshold
        )
      }
      is DumpingDisabled -> {
        event.reason
      }
      is DumpHappenedRecently -> {
        application.getString(R.string.leak_canary_notification_retained_dump_wait)
      }
    }
    SharkLog.d { message }

    handler.post {
      val resumedActivity = InternalLeakCanary.resumedActivity ?: return@post
      TvToast.makeText(resumedActivity, message).show()
    }
  }
}