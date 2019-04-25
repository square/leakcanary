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

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_SERVICES
import android.content.pm.ServiceInfo
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.JELLY_BEAN
import android.os.Build.VERSION_CODES.O
import com.squareup.leakcanary.core.R
import leakcanary.CanaryLog

internal object LeakCanaryUtils {

  const val SAMSUNG = "samsung"
  const val MOTOROLA = "motorola"
  const val LENOVO = "LENOVO"
  const val LG = "LGE"
  const val NVIDIA = "NVIDIA"
  const val MEIZU = "Meizu"
  const val HUAWEI = "HUAWEI"
  const val VIVO = "vivo"

  // Lint is wrong about what constitutes a leak. Ironic.
  @SuppressLint("StaticFieldLeak")
  @Volatile private var leakDirectoryProvider: LeakDirectoryProvider? = null

  fun getLeakDirectoryProvider(context: Context): LeakDirectoryProvider {
    var leakDirectoryProvider =
      leakDirectoryProvider
    if (leakDirectoryProvider == null) {
      leakDirectoryProvider = LeakDirectoryProvider(context)
    }
    return leakDirectoryProvider
  }

  private const val NOTIFICATION_CHANNEL_ID = "leakcanary"

  /** Extracts the class simple name out of a string containing a fully qualified class name.  */
  fun classSimpleName(className: String): String {
    val separator = className.lastIndexOf('.')
    return if (separator == -1) {
      className
    } else {
      className.substring(separator + 1)
    }
  }

  fun showNotification(
    context: Context,
    contentTitle: CharSequence,
    contentText: CharSequence,
    pendingIntent: PendingIntent?,
    notificationId: Int
  ) {
    val builder = Notification.Builder(context)
        .setContentText(contentText)
        .setContentTitle(contentTitle)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)

    val notification =
      buildNotification(context, builder)
    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    try {
      notificationManager.notify(notificationId, notification)
    } catch (ignored: SecurityException) {
      // https://github.com/square/leakcanary/issues/1197
    }
  }

  fun buildNotification(
    context: Context,
    builder: Notification.Builder
  ): Notification {
    builder.setSmallIcon(R.drawable.leak_canary_notification)
        .setWhen(System.currentTimeMillis())
        .setOnlyAlertOnce(true)

    if (SDK_INT >= O) {
      val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      var notificationChannel: NotificationChannel? =
        notificationManager.getNotificationChannel(
            NOTIFICATION_CHANNEL_ID
        )
      if (notificationChannel == null) {
        val channelName = context.getString(R.string.leak_canary_notification_channel)
        notificationChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, channelName,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(notificationChannel)
      }
      builder.setChannelId(
          NOTIFICATION_CHANNEL_ID
      )
    }

    return if (SDK_INT < JELLY_BEAN) {
      builder.notification
    } else {
      builder.build()
    }
  }
}
