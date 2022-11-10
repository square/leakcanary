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

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.JELLY_BEAN
import android.os.Build.VERSION_CODES.O
import com.squareup.leakcanary.core.R
import leakcanary.LeakCanary
import leakcanary.internal.InternalLeakCanary.FormFactor.MOBILE
import shark.SharkLog

internal object Notifications {

  private var notificationPermissionRequested = false

  // Instant apps cannot show background notifications
  // See https://github.com/square/leakcanary/issues/1197
  // TV devices can't show notifications.
  // Watch devices: not sure, but probably not a good idea anyway?
  val canShowNotification: Boolean
    get() {
      if (InternalLeakCanary.formFactor != MOBILE) {
        return false
      }
      if (InternalLeakCanary.isInstantApp || !InternalLeakCanary.applicationVisible) {
        return false
      }
      if (!LeakCanary.config.showNotifications) {
        return false
      }
      if (SDK_INT >= 33) {
        val application = InternalLeakCanary.application
        if (application.applicationInfo.targetSdkVersion >= 33) {
          val notificationManager =
            application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
          if (!notificationManager.areNotificationsEnabled()) {
            if (notificationPermissionRequested) {
              SharkLog.d { "Not showing notification: already requested missing POST_NOTIFICATIONS permission." }
            } else {
              SharkLog.d { "Not showing notification: requesting missing POST_NOTIFICATIONS permission." }
              application.startActivity(
                RequestPermissionActivity.createIntent(
                  application,
                  POST_NOTIFICATIONS
                )
              )
              notificationPermissionRequested = true
            }
            return false
          }
          if (notificationManager.areNotificationsPaused()) {
            SharkLog.d { "Not showing notification, notifications are paused." }
            return false
          }
        }
      }
      return true
    }

  @Suppress("LongParameterList")
  fun showNotification(
    context: Context,
    contentTitle: CharSequence,
    contentText: CharSequence,
    pendingIntent: PendingIntent?,
    notificationId: Int,
    type: NotificationType
  ) {
    if (!canShowNotification) {
      return
    }

    val builder = if (SDK_INT >= O) {
      Notification.Builder(context, type.name)
    } else Notification.Builder(context)

    builder
      .setContentText(contentText)
      .setContentTitle(contentTitle)
      .setAutoCancel(true)
      .setContentIntent(pendingIntent)

    val notification =
      buildNotification(context, builder, type)
    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(notificationId, notification)
  }

  fun buildNotification(
    context: Context,
    builder: Notification.Builder,
    type: NotificationType
  ): Notification {
    builder.setSmallIcon(R.drawable.leak_canary_leak)
      .setWhen(System.currentTimeMillis())

    if (SDK_INT >= O) {
      val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      var notificationChannel: NotificationChannel? =
        notificationManager.getNotificationChannel(type.name)
      if (notificationChannel == null) {
        val channelName = context.getString(type.nameResId)
        notificationChannel =
          NotificationChannel(type.name, channelName, type.importance)
        notificationManager.createNotificationChannel(notificationChannel)
      }
      builder.setChannelId(type.name)
      builder.setGroup(type.name)
    }

    return if (SDK_INT < JELLY_BEAN) {
      @Suppress("DEPRECATION")
      builder.notification
    } else {
      builder.build()
    }
  }
}
