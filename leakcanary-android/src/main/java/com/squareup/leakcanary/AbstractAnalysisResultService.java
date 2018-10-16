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
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import com.squareup.leakcanary.internal.ForegroundService;
import java.io.File;

public abstract class AbstractAnalysisResultService extends ForegroundService {

  private static final String ANALYZED_HEAP_PATH_EXTRA = "analyzed_heap_path_extra";

  public static void sendResultToListener(@NonNull Context context,
      @NonNull String listenerServiceClassName,
      @NonNull HeapDump heapDump,
      @NonNull AnalysisResult result) {
    Class<?> listenerServiceClass;
    try {
      listenerServiceClass = Class.forName(listenerServiceClassName);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    Intent intent = new Intent(context, listenerServiceClass);

    File analyzedHeapFile = AnalyzedHeap.save(heapDump, result);
    if (analyzedHeapFile != null) {
      intent.putExtra(ANALYZED_HEAP_PATH_EXTRA, analyzedHeapFile.getAbsolutePath());
    }
    ContextCompat.startForegroundService(context, intent);
  }

  public AbstractAnalysisResultService() {
    super(AbstractAnalysisResultService.class.getName(),
        R.string.leak_canary_notification_reporting);
  }

  @Override protected final void onHandleIntentInForeground(@Nullable Intent intent) {
    if (intent == null) {
      CanaryLog.d("AbstractAnalysisResultService received a null intent, ignoring.");
      return;
    }
    if (!intent.hasExtra(ANALYZED_HEAP_PATH_EXTRA)) {
      onAnalysisResultFailure(getString(R.string.leak_canary_result_failure_no_disk_space));
      return;
    }
    File analyzedHeapFile = new File(intent.getStringExtra(ANALYZED_HEAP_PATH_EXTRA));
    AnalyzedHeap analyzedHeap = AnalyzedHeap.load(analyzedHeapFile);
    if (analyzedHeap == null) {
      onAnalysisResultFailure(getString(R.string.leak_canary_result_failure_no_file));
      return;
    }
    try {
      onHeapAnalyzed(analyzedHeap);
    } finally {
      //noinspection ResultOfMethodCallIgnored
      analyzedHeap.heapDump.heapDumpFile.delete();
      //noinspection ResultOfMethodCallIgnored
      analyzedHeap.selfFile.delete();
    }
  }

  /**
   * Called after a heap dump is analyzed, whether or not a leak was found.
   * In {@link AnalyzedHeap#result} check {@link AnalysisResult#leakFound} and {@link
   * AnalysisResult#excludedLeak} to see if there was a leak and if it can be ignored.
   * <p>
   * This will be called from a background intent service thread.
   * <p>
   * It's OK to block here and wait for the heap dump to be uploaded.
   * <p>
   * The analyzed heap file and heap dump file will be deleted immediately after this callback
   * returns.
   */
  protected void onHeapAnalyzed(@NonNull AnalyzedHeap analyzedHeap) {
    onHeapAnalyzed(analyzedHeap.heapDump, analyzedHeap.result);
  }

  /**
   * @deprecated Maintained for backward compatibility. You should override {@link
   * #onHeapAnalyzed(AnalyzedHeap)} instead.
   */
  @SuppressWarnings("DeprecatedIsStillUsed")
  @Deprecated
  protected void onHeapAnalyzed(@NonNull HeapDump heapDump, @NonNull AnalysisResult result) {
  }

  /**
   * Called when there was an error saving or loading the analysis result. This will be called from
   * a background intent service thread.
   */
  protected void onAnalysisResultFailure(String failureMessage) {
    CanaryLog.d(failureMessage);
  }
}
