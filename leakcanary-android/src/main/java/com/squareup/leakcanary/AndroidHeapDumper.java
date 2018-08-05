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
package com.squareup.leakcanary;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import com.squareup.leakcanary.internal.LeakCanaryInternals;
import java.io.File;

public final class AndroidHeapDumper implements HeapDumper {

  private final Context context;
  private final LeakDirectoryProvider leakDirectoryProvider;

  public AndroidHeapDumper(Context context, LeakDirectoryProvider leakDirectoryProvider) {
    this.leakDirectoryProvider = leakDirectoryProvider;
    this.context = context.getApplicationContext();
  }

  @SuppressWarnings("ReferenceEquality") // Explicitly checking for named null.
  @Override public File dumpHeap() {
    File heapDumpFile = leakDirectoryProvider.newHeapDumpFile();

    if (heapDumpFile == RETRY_LATER) {
      return RETRY_LATER;
    }

    Notification.Builder builder = new Notification.Builder(context)
        .setContentTitle(context.getString(R.string.leak_canary_notification_dumping));
    Notification notification = LeakCanaryInternals.buildNotification(context, builder);
    NotificationManager notificationManager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    int notificationId = (int) SystemClock.uptimeMillis();
    notificationManager.notify(notificationId, notification);

    try {
      Debug.dumpHprofData(heapDumpFile.getAbsolutePath());
      notificationManager.cancel(notificationId);
      return heapDumpFile;
    } catch (Exception e) {
      CanaryLog.d(e, "Could not dump heap");
      // Abort heap dump
      return RETRY_LATER;
    }
  }
}
