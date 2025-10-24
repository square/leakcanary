package leakcanary

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import com.squareup.leakcanary.core.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.DumpingHeap
import leakcanary.EventListener.Event.HeapDump
import leakcanary.EventListener.Event.HeapDumpFailed
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.friendly.mainHandler

object ToastEventListener : EventListener {

  // Only accessed from the main thread
  private var toastCurrentlyShown: DialogInterface? = null

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
      else -> {}
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

      AlertDialog.Builder(resumedActivity)
        .setTitle(resumedActivity.packageName)
        .setIcon(R.drawable.leak_canary_icon)
        .setMessage(R.string.leak_canary_toast_heap_dump)
        .setPositiveButton(
          "View Details"
        ) { dialog, which ->
          toastCurrentlyShown = dialog
          waitingForToast.countDown()
          val intent = Intent()
          intent.setClass(resumedActivity.application, LeakActivity::class.java)
          resumedActivity.startActivity(intent)
        }
        .show()
    })
    waitingForToast.await(5, SECONDS)
  }
}
