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
import android.support.annotation.NonNull;
import com.squareup.leakcanary.internal.DisplayLeakActivity;
import com.squareup.leakcanary.internal.LeakCanaryInternals;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static android.text.format.Formatter.formatShortFileSize;
import static com.squareup.leakcanary.LeakCanary.leakInfo;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.classSimpleName;

/**
 * Logs leak analysis results, and then shows a notification which will start {@link
 * DisplayLeakActivity}.
 * <p>
 * You can extend this class and override {@link #afterDefaultHandling(HeapDump, AnalysisResult,
 * String)} to add custom behavior, e.g. uploading the heap dump.
 */
public class DisplayLeakService extends AbstractAnalysisResultService {

  @Override
  protected final void onHeapAnalyzed(@NonNull AnalyzedHeap analyzedHeap) {
    HeapDump heapDump = analyzedHeap.heapDump;
    AnalysisResult result = analyzedHeap.result;

    String leakInfo = leakInfo(this, heapDump, result, true);
    CanaryLog.d("%s", leakInfo);

    heapDump = renameHeapdump(heapDump);
    boolean resultSaved = saveResult(heapDump, result);

    String contentTitle;
    if (resultSaved) {
      PendingIntent pendingIntent =
          DisplayLeakActivity.createPendingIntent(this, heapDump.referenceKey);
      if (result.failure != null) {
        contentTitle = getString(R.string.leak_canary_analysis_failed);
      } else {
        String className = classSimpleName(result.className);
        if (result.leakFound) {
          if (result.retainedHeapSize == AnalysisResult.RETAINED_HEAP_SKIPPED) {
            if (result.excludedLeak) {
              contentTitle = getString(R.string.leak_canary_leak_excluded, className);
            } else {
              contentTitle = getString(R.string.leak_canary_class_has_leaked, className);
            }
          } else {
            String size = formatShortFileSize(this, result.retainedHeapSize);
            if (result.excludedLeak) {
              contentTitle =
                  getString(R.string.leak_canary_leak_excluded_retaining, className, size);
            } else {
              contentTitle =
                  getString(R.string.leak_canary_class_has_leaked_retaining, className, size);
            }
          }
        } else {
          contentTitle = getString(R.string.leak_canary_class_no_leak, className);
        }
      }
      String contentText = getString(R.string.leak_canary_notification_message);
      showNotification(pendingIntent, contentTitle, contentText);
    } else {
      onAnalysisResultFailure(getString(R.string.leak_canary_could_not_save_text));
    }

    afterDefaultHandling(heapDump, result, leakInfo);
  }

  @Override protected final void onAnalysisResultFailure(String failureMessage) {
    super.onAnalysisResultFailure(failureMessage);
    String failureTitle = getString(R.string.leak_canary_result_failure_title);
    showNotification(null, failureTitle, failureMessage);
  }

  private void showNotification(PendingIntent pendingIntent, String contentTitle,
      String contentText) {
    // New notification id every second.
    int notificationId = (int) (SystemClock.uptimeMillis() / 1000);
    LeakCanaryInternals.showNotification(this, contentTitle, contentText, pendingIntent,
        notificationId);
  }

  private boolean saveResult(HeapDump heapDump, AnalysisResult result) {
    File resultFile = AnalyzedHeap.save(heapDump, result);
    return resultFile != null;
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
    return heapDump.buildUpon().heapDumpFile(newFile).build();
  }

  /**
   * You can override this method and do a blocking call to a server to upload the leak trace and
   * the heap dump. Don't forget to check {@link AnalysisResult#leakFound} and {@link
   * AnalysisResult#excludedLeak} first.
   */
  protected void afterDefaultHandling(@NonNull HeapDump heapDump, @NonNull AnalysisResult result,
      @NonNull String leakInfo) {
  }
}
