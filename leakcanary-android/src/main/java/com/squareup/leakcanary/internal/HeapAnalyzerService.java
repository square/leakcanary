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

import android.content.Context;
import android.content.Intent;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import com.squareup.leakcanary.AbstractAnalysisResultService;
import com.squareup.leakcanary.AnalysisResult;
import com.squareup.leakcanary.AnalyzerProgressListener;
import com.squareup.leakcanary.CanaryLog;
import com.squareup.leakcanary.HeapAnalyzer;
import com.squareup.leakcanary.HeapDump;
import com.squareup.leakcanary.R;

import static com.squareup.leakcanary.internal.LeakCanaryInternals.setEnabledBlocking;

/**
 * This service runs in a separate process to avoid slowing down the app process or making it run
 * out of memory.
 */
public final class HeapAnalyzerService extends ForegroundService
    implements AnalyzerProgressListener {

  private static final String LISTENER_CLASS_EXTRA = "listener_class_extra";
  private static final String HEAPDUMP_EXTRA = "heapdump_extra";

  public static void runAnalysis(Context context, HeapDump heapDump,
      Class<? extends AbstractAnalysisResultService> listenerServiceClass) {
    setEnabledBlocking(context, HeapAnalyzerService.class, true);
    setEnabledBlocking(context, listenerServiceClass, true);
    Intent intent = new Intent(context, HeapAnalyzerService.class);
    intent.putExtra(LISTENER_CLASS_EXTRA, listenerServiceClass.getName());
    intent.putExtra(HEAPDUMP_EXTRA, heapDump);
    ContextCompat.startForegroundService(context, intent);
  }

  public HeapAnalyzerService() {
    super(HeapAnalyzerService.class.getSimpleName(), R.string.leak_canary_notification_analysing);
  }

  @Override protected void onHandleIntentInForeground(@Nullable Intent intent) {
    if (intent == null) {
      CanaryLog.d("HeapAnalyzerService received a null intent, ignoring.");
      return;
    }
    String listenerClassName = intent.getStringExtra(LISTENER_CLASS_EXTRA);
    HeapDump heapDump = (HeapDump) intent.getSerializableExtra(HEAPDUMP_EXTRA);

    HeapAnalyzer heapAnalyzer =
        new HeapAnalyzer(heapDump.excludedRefs, this, heapDump.reachabilityInspectorClasses);

    AnalysisResult result = heapAnalyzer.checkForLeak(heapDump.heapDumpFile, heapDump.referenceKey,
        heapDump.computeRetainedHeapSize);
    AbstractAnalysisResultService.sendResultToListener(this, listenerClassName, heapDump, result);
  }

  @Override public void onProgressUpdate(Step step) {
    int percent = (int) ((100f * step.ordinal()) / Step.values().length);
    CanaryLog.d("Analysis in progress, working on: %s", step.name());
    String lowercase = step.name().replace("_", " ").toLowerCase();
    String message = lowercase.substring(0, 1).toUpperCase() + lowercase.substring(1);
    showForegroundNotification(100, percent, false, message);
  }
}
