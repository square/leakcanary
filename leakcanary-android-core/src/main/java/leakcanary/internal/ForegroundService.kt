/*
 * Copyright (C) 2018 Square, Inc.
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

import android.app.IntentService
import android.app.Notification
import android.content.Intent
import android.os.IBinder
import com.squareup.leakcanary.core.R
import leakcanary.internal.NotificationType.LEAKCANARY_LOW

internal abstract class ForegroundService(
  name: String,
  private val notificationContentTitleResId: Int,
  private val notificationId: Int
) : IntentService(name) {

  override fun onCreate() {
    super.onCreate()
    showForegroundNotification(
      max = 100, progress = 0, indeterminate = true,
      contentText = getString(R.string.leak_canary_notification_foreground_text)
    )
  }

  protected fun showForegroundNotification(
    max: Int,
    progress: Int,
    indeterminate: Boolean,
    contentText: String
  ) {
    val builder = Notification.Builder(this)
      .setContentTitle(getString(notificationContentTitleResId))
      .setContentText(contentText)
      .setProgress(max, progress, indeterminate)
    val notification =
      Notifications.buildNotification(this, builder, LEAKCANARY_LOW)
    startForeground(notificationId, notification)
  }

  override fun onHandleIntent(intent: Intent?) {
    onHandleIntentInForeground(intent)
  }

  protected abstract fun onHandleIntentInForeground(intent: Intent?)

  override fun onDestroy() {
    super.onDestroy()
    stopForeground(true)
  }

  override fun onBind(intent: Intent): IBinder? {
    return null
  }
}
