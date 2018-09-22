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

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.ContextCompat;
import com.squareup.leakcanary.internal.AnalysisResultAccessor;
import com.squareup.leakcanary.internal.ForegroundService;
import com.squareup.leakcanary.internal.Leak;

import java.io.File;

public abstract class AbstractAnalysisResultService extends ForegroundService {

  private static final String RESULT_FILE_PATH_EXTRA = "result_file_path_extra";

  public static void sendResultToListener(Context context, String listenerServiceClassName,
                                          File result) {
    Class<?> listenerServiceClass;
    try {
      listenerServiceClass = Class.forName(listenerServiceClassName);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    Intent intent = new Intent(context, listenerServiceClass);

    intent.putExtra(RESULT_FILE_PATH_EXTRA, result.getAbsolutePath());
    ContextCompat.startForegroundService(context, intent);
  }

  private final AnalysisResultAccessor accessor = new AnalysisResultAccessor();

  public AbstractAnalysisResultService() {
    super(AbstractAnalysisResultService.class.getName(),
        R.string.leak_canary_notification_reporting);
  }

  @Override
  protected final void onHandleIntentInForeground(Intent intent) {
    String heapDump = intent.getStringExtra(RESULT_FILE_PATH_EXTRA);
    final Leak leak = accessor.loadLeak(new File(heapDump));
    if (leak != null) {
      onHeapAnalyzed(leak.heapDump, leak.result);
    } else {
      onHeapAnalyzed(null, null);
    }
  }

  /**
   * Called after a heap dump is analyzed, whether or not a leak was found.
   * Check {@link AnalysisResult#leakFound} and {@link AnalysisResult#excludedLeak} to see if there
   * was a leak and if it can be ignored.
   * <p>
   * This will be called from a background intent service thread.
   * <p>
   * It's OK to block here and wait for the heap dump to be uploaded.
   * <p>
   * The heap dump file will be deleted immediately after this callback returns.
   */
  protected abstract void onHeapAnalyzed(HeapDump heapDump, AnalysisResult result);
}
