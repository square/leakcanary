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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.JELLY_BEAN
import android.os.Build.VERSION_CODES.O
import com.squareup.leakcanary.core.R

internal object Notifications {

  val canShowNotification: Boolean
    get() = canShowBackgroundNotifications || InternalLeakCanary.applicationVisible

  private val canShowBackgroundNotifications = if (SDK_INT >= O) {
    // Instants apps cannot show background notifications
    // See https://github.com/square/leakcanary/issues/1197
    !InternalLeakCanary.application.packageManager.isInstantApp
  } else true

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
    val builder = Notification.Builder(context)
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
    builder.setSmallIcon(R.drawable.leak_canary_notification)
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
    }

    return if (SDK_INT < JELLY_BEAN) {
      @Suppress("DEPRECATION")
      builder.notification
    } else {
      builder.build()
    }
  }
}
