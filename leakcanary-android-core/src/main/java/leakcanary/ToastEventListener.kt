package leakcanary

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.squareup.leakcanary.core.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.DumpingHeap
import leakcanary.EventListener.Event.HeapDumpFailed
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.friendly.mainHandler

object ToastEventListener : EventListener {

  // Only accessed from the main thread
  private var toastCurrentlyShown: Toast? = null

  override fun onEvent(event: Event) {
    when (event) {
      is DumpingHeap -> {
        showToastBlocking()
      }
      is HeapDump, is HeapDumpFailed -> {
        mainHandler.post {
          toastCurrentlyShown?.cancel()
          toastCurrentlyShown = null
        }
      }
    }
  }

  @Suppress("DEPRECATION")
  private fun showToastBlocking() {
    val appContext = InternalLeakCanary.application
    val waitingForToast = CountDownLatch(1)
    mainHandler.post(Runnable {
      val resumedActivity = InternalLeakCanary.resumedActivity
      if (resumedActivity == null || toastCurrentlyShown != null) {
        waitingForToast.countDown()
        return@Runnable
      }
      val toast = Toast(resumedActivity)
      // Resources from application context: https://github.com/square/leakcanary/issues/2023
      val iconSize = appContext.resources.getDimensionPixelSize(
        R.dimen.leak_canary_toast_icon_size
      )
      toast.setGravity(Gravity.CENTER_VERTICAL, 0, -iconSize)
      toast.duration = Toast.LENGTH_LONG
      // Need an activity context because StrictMode added new stupid checks:
      // https://github.com/square/leakcanary/issues/2153
      val inflater = LayoutInflater.from(resumedActivity)
      toast.view = inflater.inflate(R.layout.leak_canary_heap_dump_toast, null)
      toast.show()

      val toastIcon = toast.view!!.findViewById<View>(R.id.leak_canary_toast_icon)
      toastIcon.translationY = -iconSize.toFloat()
      toastIcon
        .animate()
        .translationY(0f)
        .setListener(object : AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: Animator) {
            toastCurrentlyShown = toast
            waitingForToast.countDown()
          }
        })
    })
    waitingForToast.await(5, SECONDS)
  }
}
