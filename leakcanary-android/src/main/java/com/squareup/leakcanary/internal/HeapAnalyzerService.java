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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.IBinder;
import com.squareup.leakcanary.AbstractAnalysisResultService;
import com.squareup.leakcanary.AnalysisResult;
import com.squareup.leakcanary.CanaryLog;
import com.squareup.leakcanary.HeapAnalyzer;
import com.squareup.leakcanary.HeapDump;
import com.squareup.leakcanary.R;

/**
 * This service runs in a separate process to avoid slowing down the app process or making it run
 * out of memory.
 */
public final class HeapAnalyzerService extends Service {

  private static final String LISTENER_CLASS_EXTRA = "listener_class_extra";
  private static final String HEAPDUMP_EXTRA = "heapdump_extra";

  public static void runAnalysis(Context context, HeapDump heapDump,
      Class<? extends AbstractAnalysisResultService> listenerServiceClass) {
    Intent intent = new Intent(context, HeapAnalyzerService.class);
    intent.putExtra(LISTENER_CLASS_EXTRA, listenerServiceClass.getName());
    intent.putExtra(HEAPDUMP_EXTRA, heapDump);
    context.startService(intent);
  }

  @Override
  public void onCreate() {
    super.onCreate();

    Notification.Builder notificationBuilder = new Notification.Builder(this);
    notificationBuilder.setContentTitle("Leak Canary Analyzer Service");
    notificationBuilder.setSmallIcon(R.drawable.leak_canary_notification);


    if (VERSION.SDK_INT >= VERSION_CODES.O) {
      LeakCanaryInternals.setupNotificationChannel(
          this,
          (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE),
          notificationBuilder);
    }

    Notification notification;

    if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
      notificationBuilder.setPriority(Notification.PRIORITY_MIN);
      notification = notificationBuilder.build();
    } else {
      notification = notificationBuilder.getNotification();
    }

    startForeground(123, notification);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) {
      CanaryLog.d("HeapAnalyzerService received a null intent, ignoring.");
      stopForeground(true);
      stopSelf();
    }

    String listenerClassName = intent.getStringExtra(LISTENER_CLASS_EXTRA);
    HeapDump heapDump = (HeapDump) intent.getSerializableExtra(HEAPDUMP_EXTRA);

    HeapAnalyzer heapAnalyzer = new HeapAnalyzer(heapDump.excludedRefs);

    AnalysisResult result = heapAnalyzer.checkForLeak(heapDump.heapDumpFile, heapDump.referenceKey);
    AbstractAnalysisResultService.sendResultToListener(this, listenerClassName, heapDump, result);

    stopForeground(true);
    stopSelf();

    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
