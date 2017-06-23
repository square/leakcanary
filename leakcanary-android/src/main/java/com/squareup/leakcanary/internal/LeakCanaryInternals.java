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
package com.squareup.leakcanary.internal;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import com.squareup.leakcanary.CanaryLog;
import com.squareup.leakcanary.R;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.content.pm.PackageManager.GET_SERVICES;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.JELLY_BEAN;
import static android.os.Build.VERSION_CODES.O;

public final class LeakCanaryInternals {

  public static final String SAMSUNG = "samsung";
  public static final String MOTOROLA = "motorola";
  public static final String LENOVO = "LENOVO";
  public static final String LG = "LGE";
  public static final String NVIDIA = "NVIDIA";
  public static final String MEIZU = "Meizu";
  public static final String HUAWEI = "HUAWEI";

  private static final Executor fileIoExecutor = newSingleThreadExecutor("File-IO");

  private static final String NOTIFICATION_CHANNEL_ID = "leakcanary";

  public static void executeOnFileIoThread(Runnable runnable) {
    fileIoExecutor.execute(runnable);
  }

  /** Extracts the class simple name out of a string containing a fully qualified class name. */
  public static String classSimpleName(String className) {
    int separator = className.lastIndexOf('.');
    if (separator == -1) {
      return className;
    } else {
      return className.substring(separator + 1);
    }
  }

  public static void setEnabled(Context context, final Class<?> componentClass,
      final boolean enabled) {
    final Context appContext = context.getApplicationContext();
    executeOnFileIoThread(new Runnable() {
      @Override public void run() {
        setEnabledBlocking(appContext, componentClass, enabled);
      }
    });
  }

  public static void setEnabledBlocking(Context appContext, Class<?> componentClass,
      boolean enabled) {
    ComponentName component = new ComponentName(appContext, componentClass);
    PackageManager packageManager = appContext.getPackageManager();
    int newState = enabled ? COMPONENT_ENABLED_STATE_ENABLED : COMPONENT_ENABLED_STATE_DISABLED;
    // Blocks on IPC.
    packageManager.setComponentEnabledSetting(component, newState, DONT_KILL_APP);
  }

  public static boolean isInServiceProcess(Context context, Class<? extends Service> serviceClass) {
    PackageManager packageManager = context.getPackageManager();
    PackageInfo packageInfo;
    try {
      packageInfo = packageManager.getPackageInfo(context.getPackageName(), GET_SERVICES);
    } catch (Exception e) {
      CanaryLog.d(e, "Could not get package info for %s", context.getPackageName());
      return false;
    }
    String mainProcess = packageInfo.applicationInfo.processName;

    ComponentName component = new ComponentName(context, serviceClass);
    ServiceInfo serviceInfo;
    try {
      serviceInfo = packageManager.getServiceInfo(component, 0);
    } catch (PackageManager.NameNotFoundException ignored) {
      // Service is disabled.
      return false;
    }

    if (serviceInfo.processName.equals(mainProcess)) {
      CanaryLog.d("Did not expect service %s to run in main process %s", serviceClass, mainProcess);
      // Technically we are in the service process, but we're not in the service dedicated process.
      return false;
    }

    int myPid = android.os.Process.myPid();
    ActivityManager activityManager =
        (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    ActivityManager.RunningAppProcessInfo myProcess = null;
    List<ActivityManager.RunningAppProcessInfo> runningProcesses =
        activityManager.getRunningAppProcesses();
    if (runningProcesses != null) {
      for (ActivityManager.RunningAppProcessInfo process : runningProcesses) {
        if (process.pid == myPid) {
          myProcess = process;
          break;
        }
      }
    }
    if (myProcess == null) {
      CanaryLog.d("Could not find running process for %d", myPid);
      return false;
    }

    return myProcess.processName.equals(serviceInfo.processName);
  }

  @TargetApi(O)
  public static void showNotification(Context context, CharSequence contentTitle,
      CharSequence contentText, PendingIntent pendingIntent, int notificationId) {
    NotificationManager notificationManager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    Notification notification;
    Notification.Builder builder = new Notification.Builder(context) //
        .setSmallIcon(R.drawable.leak_canary_notification)
        .setWhen(System.currentTimeMillis())
        .setContentTitle(contentTitle)
        .setContentText(contentText)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent);
    if (SDK_INT >= O) {
      if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
        NotificationChannel notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.leak_canary_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT);
        notificationManager.createNotificationChannel(notificationChannel);
      }
      builder.setChannelId(NOTIFICATION_CHANNEL_ID);
    }
    if (SDK_INT < JELLY_BEAN) {
      notification = builder.getNotification();
    } else {
      notification = builder.build();
    }
    notificationManager.notify(notificationId, notification);
  }

  public static Executor newSingleThreadExecutor(String threadName) {
    return Executors.newSingleThreadExecutor(new LeakCanarySingleThreadFactory(threadName));
  }

  private LeakCanaryInternals() {
    throw new AssertionError();
  }
}
