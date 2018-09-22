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

import android.app.PendingIntent;
import android.os.SystemClock;
import com.squareup.leakcanary.internal.DisplayLeakActivity;

import static android.text.format.Formatter.formatShortFileSize;
import static com.squareup.leakcanary.LeakCanary.leakInfo;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.classSimpleName;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.showNotification;

/**
 * Logs leak analysis results, and then shows a notification which will start {@link
 * DisplayLeakActivity}.
 * <p>
 * You can extend this class and override {@link #afterDefaultHandling(HeapDump, AnalysisResult,
 * String)} to add custom behavior, e.g. uploading the heap dump.
 */
public class DisplayLeakService extends AbstractAnalysisResultService {

  @Override
  protected final void onHeapAnalyzed(HeapDump heapDump, AnalysisResult result) {
    String leakInfo;
    boolean resultSaved;
    if (heapDump == null) {
      leakInfo = leakInfo(this, heapDump, result, true);
      CanaryLog.d("%s", leakInfo);
      resultSaved = false;
    } else {
      leakInfo = "No leak info";
      resultSaved = true;
    }

    PendingIntent pendingIntent;
    String contentTitle;
    String contentText;

    if (resultSaved) {
      pendingIntent = DisplayLeakActivity.createPendingIntent(this, heapDump.referenceKey);

      if (result.failure == null) {
        if (result.retainedHeapSize == AnalysisResult.RETAINED_HEAP_SKIPPED) {
          String className = classSimpleName(result.className);
          if (result.excludedLeak) {
            contentTitle = getString(R.string.leak_canary_leak_excluded, className);
          } else {
            contentTitle = getString(R.string.leak_canary_class_has_leaked, className);
          }
        } else {
          String size = formatShortFileSize(this, result.retainedHeapSize);
          String className = classSimpleName(result.className);
          if (result.excludedLeak) {
            contentTitle = getString(R.string.leak_canary_leak_excluded_retaining, className, size);
          } else {
            contentTitle =
                getString(R.string.leak_canary_class_has_leaked_retaining, className, size);
          }
        }
      } else {
        contentTitle = getString(R.string.leak_canary_analysis_failed);
      }
      contentText = getString(R.string.leak_canary_notification_message);
    } else {
      contentTitle = getString(R.string.leak_canary_no_leak_title);
      contentText = getString(R.string.leak_canary_no_leak_text);
      pendingIntent = null;
    }
    // New notification id every second.
    int notificationId = (int) (SystemClock.uptimeMillis() / 1000);
    showNotification(this, contentTitle, contentText, pendingIntent, notificationId);
    afterDefaultHandling(heapDump, result, leakInfo);
  }


  /**
   * You can override this method and do a blocking call to a server to upload the leak trace and
   * the heap dump. Don't forget to check {@link AnalysisResult#leakFound} and {@link
   * AnalysisResult#excludedLeak} first.
   */
  protected void afterDefaultHandling(HeapDump heapDump, AnalysisResult result, String leakInfo) {
  }
}
