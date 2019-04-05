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
package leakcanary

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Toast
import com.squareup.leakcanary.R.layout
import com.squareup.leakcanary.R.string
import leakcanary.internal.FutureResult
import leakcanary.internal.LeakCanaryInternals
import leakcanary.internal.ActivityLifecycleCallbacksAdapter
import java.io.File
import java.util.concurrent.TimeUnit.SECONDS

class AndroidHeapDumper(
  context: Context,
  private val leakDirectoryProvider: LeakDirectoryProvider
) : HeapDumper {

  private val context: Context = context.applicationContext
  private val mainHandler: Handler = Handler(Looper.getMainLooper())

  private var resumedActivity: Activity? = null

  init {
    val application = context.applicationContext as Application
    application.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacksAdapter() {
      override fun onActivityResumed(activity: Activity) {
        resumedActivity = activity
      }

      override fun onActivityPaused(activity: Activity) {
        if (resumedActivity === activity) {
          resumedActivity = null
        }
      }
    })
  }

  override// Explicitly checking for named null.
  fun dumpHeap(): File? {
    val heapDumpFile = leakDirectoryProvider.newHeapDumpFile()

    if (heapDumpFile === HeapDumper.RETRY_LATER) {
      return HeapDumper.RETRY_LATER
    }

    val waitingForToast = FutureResult<Toast?>()
    showToast(waitingForToast)

    if (!waitingForToast.wait(5, SECONDS)) {
      CanaryLog.d("Did not dump heap, too much time waiting for Toast.")
      return HeapDumper.RETRY_LATER
    }

    val builder = Notification.Builder(context)
        .setContentTitle(context.getString(
            string.leak_canary_notification_dumping
        ))
    val notification = LeakCanaryInternals.buildNotification(context, builder)
    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notificationId = SystemClock.uptimeMillis()
        .toInt()
    notificationManager.notify(notificationId, notification)

    val toast = waitingForToast.get()

    try {
      Debug.dumpHprofData(heapDumpFile!!.absolutePath)
      cancelToast(toast)
      notificationManager.cancel(notificationId)
      return heapDumpFile
    } catch (e: Exception) {
      CanaryLog.d(e, "Could not dump heap")
      // Abort heap dump
      return HeapDumper.RETRY_LATER
    }

  }

  private fun showToast(waitingForToast: FutureResult<Toast?>) {
    mainHandler.post(Runnable {
      if (resumedActivity == null) {
        waitingForToast.set(null)
        return@Runnable
      }
      val toast = Toast(resumedActivity)
      toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0)
      toast.duration = Toast.LENGTH_LONG
      val inflater = LayoutInflater.from(resumedActivity)
      toast.view = inflater.inflate(layout.leak_canary_heap_dump_toast, null)
      toast.show()
      // Waiting for Idle to make sure Toast gets rendered.
      Looper.myQueue()
          .addIdleHandler {
            waitingForToast.set(toast)
            false
          }
    })
  }

  private fun cancelToast(toast: Toast?) {
    if (toast == null) {
      return
    }
    mainHandler.post { toast.cancel() }
  }
}
