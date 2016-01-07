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
import android.content.res.Resources;
import com.squareup.leakcanary.internal.DisplayLeakActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.HONEYCOMB;
import static android.os.Build.VERSION_CODES.JELLY_BEAN;
import static android.text.format.Formatter.formatShortFileSize;
import static com.squareup.leakcanary.LeakCanary.leakInfo;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.classSimpleName;

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

    boolean resultSaved = false;
    boolean shouldSaveResult = result.leakFound || result.failure != null;
    if (shouldSaveResult) {
      heapDump = renameHeapdump(heapDump);
      resultSaved = saveResult(heapDump, result);
    }

    PendingIntent pendingIntent;
    String contentTitle;
    String contentText;

    if (!shouldSaveResult) {
      contentTitle = getString(R.string.leak_canary_no_leak_title);
      contentText = getString(R.string.leak_canary_no_leak_text);
      pendingIntent = null;
    } else if (resultSaved) {
      pendingIntent = DisplayLeakActivity.createPendingIntent(this, heapDump.referenceKey);

      if (result.failure == null) {
        String size = formatShortFileSize(this, result.retainedHeapSize);
        String className = classSimpleName(result.className);
        if (result.excludedLeak) {
          contentTitle = getString(R.string.leak_canary_leak_excluded, className, size);
        } else {
          contentTitle = getString(R.string.leak_canary_class_has_leaked, className, size);
        }
      } else {
        contentTitle = getString(R.string.leak_canary_analysis_failed);
      }
      contentText = getString(R.string.leak_canary_notification_message);
    } else {
      contentTitle = getString(R.string.leak_canary_could_not_save_title);
      contentText = getString(R.string.leak_canary_could_not_save_text);
      pendingIntent = null;
    }
    notify(contentTitle, contentText, pendingIntent);
    afterDefaultHandling(heapDump, result, leakInfo);
  }

  private boolean saveResult(HeapDump heapDump, AnalysisResult result) {
    File resultFile = new File(heapDump.heapDumpFile.getParentFile(),
        heapDump.heapDumpFile.getName() + ".result");
    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(resultFile);
      ObjectOutputStream oos = new ObjectOutputStream(fos);
      oos.writeObject(heapDump);
      oos.writeObject(result);
      return true;
    } catch (IOException e) {
      CanaryLog.d(e, "Could not save leak analysis result to disk.");
    } finally {
      if (fos != null) {
        try {
          fos.close();
        } catch (IOException ignored) {
        }
      }
    }
    return false;
  }

  private HeapDump renameHeapdump(HeapDump heapDump) {
    String fileName =
        new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'.hprof'", Locale.US).format(new Date());

    File newFile = new File(heapDump.heapDumpFile.getParent(), fileName);
    boolean renamed = heapDump.heapDumpFile.renameTo(newFile);
    if (!renamed) {
      CanaryLog.d("Could not rename heap dump file %s to %s", heapDump.heapDumpFile.getPath(),
          newFile.getPath());
    }
    heapDump =
        new HeapDump(newFile, heapDump.referenceKey, heapDump.referenceName, heapDump.excludedRefs,
            heapDump.watchDurationMs, heapDump.gcDurationMs, heapDump.heapDumpDurationMs);

    Resources resources = getResources();
    int maxStoredHeapDumps =
        Math.max(resources.getInteger(R.integer.leak_canary_max_stored_leaks), 1);
    File[] hprofFiles = heapDump.heapDumpFile.getParentFile().listFiles(new FilenameFilter() {
      @Override public boolean accept(File dir, String filename) {
        return filename.endsWith(".hprof");
      }
    });

    if (hprofFiles.length > maxStoredHeapDumps) {
      // Sort with oldest modified first.
      Arrays.sort(hprofFiles, new Comparator<File>() {
        @Override public int compare(File lhs, File rhs) {
          return Long.valueOf(lhs.lastModified()).compareTo(rhs.lastModified());
        }
      });
      boolean deleted = hprofFiles[0].delete();
      if (!deleted) {
        CanaryLog.d("Could not delete old hprof file %s", hprofFiles[0].getPath());
      }
    }
    return heapDump;
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
