package com.squareup.leakcanary;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;

/**
 * Dummy class for no-op version.
 */
public abstract class AbstractAnalysisResultService extends IntentService {
  public static void sendResultToListener(Context context, String listenerServiceClassName,
      HeapDump heapDump, AnalysisResult result) {
  }

  public AbstractAnalysisResultService() {
    super(AbstractAnalysisResultService.class.getName());
  }

  @Override protected final void onHandleIntent(Intent intent) {
  }

  protected abstract void onHeapAnalyzed(HeapDump heapDump, AnalysisResult result);
}
