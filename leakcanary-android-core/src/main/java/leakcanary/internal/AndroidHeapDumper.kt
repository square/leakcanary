/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leakcanary.internal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.squareup.leakcanary.core.R
import leakcanary.internal.NotificationType.LEAKCANARY_LOW
import leakcanary.internal.utils.measureDurationMillis
import shark.SharkLog
import java.util.concurrent.TimeUnit.SECONDS

internal class AndroidHeapDumper(
  context: Context,
  private val leakDirectoryProvider: LeakDirectoryProvider
) : HeapDumper {

  private val context: Context = context.applicationContext
  private val mainHandler: Handler = Handler(Looper.getMainLooper())

  override fun dumpHeap(): DumpHeapResult {
    val heapDumpFile = leakDirectoryProvider.newHeapDumpFile() ?: return NoHeapDump

    val waitingForToast = FutureResult<Toast?>()
    showToast(waitingForToast)

    if (!waitingForToast.wait(5, SECONDS)) {
      SharkLog.d { "Did not dump heap, too much time waiting for Toast." }
      return NoHeapDump
    }

    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Notifications.canShowNotification) {
      val dumpingHeap = context.getString(R.string.leak_canary_notification_dumping)
      val builder = Notification.Builder(context)
          .setContentTitle(dumpingHeap)
      val notification = Notifications.buildNotification(context, builder, LEAKCANARY_LOW)
      notificationManager.notify(R.id.leak_canary_notification_dumping_heap, notification)
    }

    val toast = waitingForToast.get()

    return try {
      val durationMillis = measureDurationMillis {
        Debug.dumpHprofData(heapDumpFile.absolutePath)
      }
      if (heapDumpFile.length() == 0L) {
        SharkLog.d { "Dumped heap file is 0 byte length" }
        NoHeapDump
      } else {
        HeapDump(file = heapDumpFile, durationMillis = durationMillis)
      }
    } catch (e: Exception) {
      SharkLog.d(e) { "Could not dump heap" }
      // Abort heap dump
      NoHeapDump
    } finally {
      cancelToast(toast)
      notificationManager.cancel(R.id.leak_canary_notification_dumping_heap)
    }
  }

  private fun showToast(waitingForToast: FutureResult<Toast?>) {
    mainHandler.post(Runnable {
      val resumedActivity = InternalLeakCanary.resumedActivity
      if (resumedActivity == null) {
        waitingForToast.set(null)
        return@Runnable
      }
      val toast = Toast(resumedActivity)
      val iconSize = resumedActivity.resources.getDimensionPixelSize(
          R.dimen.leak_canary_toast_icon_size
      )
      toast.setGravity(Gravity.CENTER_VERTICAL, 0, -iconSize)
      toast.duration = Toast.LENGTH_LONG
      // Inflating with application context: https://github.com/square/leakcanary/issues/1385
      val inflater = LayoutInflater.from(context)
      toast.view = inflater.inflate(R.layout.leak_canary_heap_dump_toast, null)
      toast.show()

      val toastIcon = toast.view.findViewById<View>(R.id.leak_canary_toast_icon)
      toastIcon.translationY = -iconSize.toFloat()
      toastIcon
          .animate()
          .translationY(0f)
          .setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
              waitingForToast.set(toast)
            }
          })
    })
  }

  private fun cancelToast(toast: Toast?) {
    if (toast == null) {
      return
    }
    mainHandler.post { toast.cancel() }
  }
}
