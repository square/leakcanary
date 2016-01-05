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

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import com.squareup.leakcanary.internal.DisplayLeakActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.HONEYCOMB;
import static android.os.Build.VERSION_CODES.JELLY_BEAN;
import static com.squareup.leakcanary.LeakCanary.leakInfo;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.classSimpleName;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.findNextAvailableHprofFile;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.leakResultFile;

/**
 * Logs leak analysis results, and then shows a notification which will start {@link
 * DisplayLeakActivity}.
 *
 * You can extend this class and override {@link #afterDefaultHandling(HeapDump, AnalysisResult,
 * String)} to add custom behavior, e.g. uploading the heap dump.
 */
public class DisplayLeakService extends AbstractAnalysisResultService {

  @Override protected final void onHeapAnalyzed(HeapDump heapDump, AnalysisResult result) {
    String leakInfo = leakInfo(this, heapDump, result, true);
    CanaryLog.d(leakInfo);

    if (result.failure == null && (!result.leakFound || result.excludedLeak)) {
      if (result.excludedLeak) {
        PendingIntent pendingIntent = DisplayLeakActivity.createPendingIntent(this);
        String contentTitle =
            getString(R.string.leak_canary_class_leak_ignored, classSimpleName(result.className));
        String contentText = getString(R.string.leak_canary_notification_leak_ignored_message);
        notify(contentTitle, contentText, pendingIntent);
      }
      afterDefaultHandling(heapDump, result, leakInfo);
      return;
    }

    int maxStoredLeaks = getResources().getInteger(R.integer.leak_canary_max_stored_leaks);
    File renamedFile = findNextAvailableHprofFile(maxStoredLeaks);

    if (renamedFile == null) {
      // No file available.
      CanaryLog.d("Leak result dropped because we already store %d leak traces.", maxStoredLeaks);
      afterDefaultHandling(heapDump, result, leakInfo);
      return;
    }

    heapDump = heapDump.renameFile(renamedFile);

    File resultFile = leakResultFile(renamedFile);
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(resultFile);
      ObjectOutputStream oos = new ObjectOutputStream(fos);
      oos.writeObject(heapDump);
      oos.writeObject(result);
    } catch (IOException e) {
      CanaryLog.d(e, "Could not save leak analysis result to disk");
      afterDefaultHandling(heapDump, result, leakInfo);
      return;
    } finally {
      if (fos != null) {
        try {
          fos.close();
        } catch (IOException ignored) {
        }
      }
    }

    PendingIntent pendingIntent =
        DisplayLeakActivity.createPendingIntent(this, heapDump.referenceKey);

    String contentTitle;
    if (result.failure == null) {
      contentTitle =
          getString(R.string.leak_canary_class_has_leaked, classSimpleName(result.className));
    } else {
      contentTitle = getString(R.string.leak_canary_analysis_failed);
    }
    String contentText = getString(R.string.leak_canary_notification_message);

    notify(contentTitle, contentText, pendingIntent);
    afterDefaultHandling(heapDump, result, leakInfo);
  }

  @TargetApi(HONEYCOMB)
  private void notify(String contentTitle, String contentText, PendingIntent pendingIntent) {
    NotificationManager notificationManager =
        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

    Notification notification;
    if (SDK_INT < HONEYCOMB) {
      notification = new Notification();
      notification.icon = R.drawable.leak_canary_notification;
      notification.when = System.currentTimeMillis();
      notification.flags |= Notification.FLAG_AUTO_CANCEL;
      notification.setLatestEventInfo(this, contentTitle, contentText, pendingIntent);
    } else {
      Notification.Builder builder = new Notification.Builder(this) //
          .setSmallIcon(R.drawable.leak_canary_notification)
          .setWhen(System.currentTimeMillis())
          .setContentTitle(contentTitle)
          .setContentText(contentText)
          .setAutoCancel(true)
          .setContentIntent(pendingIntent);
      if (SDK_INT < JELLY_BEAN) {
        notification = builder.getNotification();
      } else {
        notification = builder.build();
      }
    }
    notificationManager.notify(0xDEAFBEEF, notification);
  }

  /**
   * You can override this method and do a blocking call to a server to upload the leak trace and
   * the heap dump. Don't forget to check {@link AnalysisResult#leakFound} and {@link
   * AnalysisResult#excludedLeak} first.
   */
  protected void afterDefaultHandling(HeapDump heapDump, AnalysisResult result, String leakInfo) {
  }
}
