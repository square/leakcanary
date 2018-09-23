/*
 * Copyright (C) 2018 Square, Inc.
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

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.test.internal.runner.listener.InstrumentationResultPrinter;
import java.util.List;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import static android.support.test.InstrumentationRegistry.getInstrumentation;
import static android.support.test.internal.runner.listener.InstrumentationResultPrinter.REPORT_VALUE_RESULT_FAILURE;
import static com.squareup.leakcanary.Preconditions.checkNotNull;

/**
 * <p>A JUnit {@link RunListener} for detecting memory leaks in Android instrumentation tests. It
 * waits for the end of a test, and if the test succeeds then it will look for leaking
 * references, trigger a heap dump if needed and perform an analysis.
 * <p> {@link FailTestOnLeakRunListener} can be subclassed to override
 * {@link #skipLeakDetectionReason(Description)}, {@link #reportLeaks(InstrumentationLeakResults)}
 * or {@link #buildLeakDetectedMessage(List)}
 *
 * @see InstrumentationLeakDetector
 */
public class FailTestOnLeakRunListener extends RunListener {

  private static final String SEPARATOR = "######################################\n";
  private Bundle bundle;

  private String skipLeakDetectionReason;

  @Override public final void testStarted(Description description) {
    skipLeakDetectionReason = skipLeakDetectionReason(description);
    if (skipLeakDetectionReason != null) {
      return;
    }
    String testClass = description.getClassName();
    String testName = description.getMethodName();

    bundle = new Bundle();
    bundle.putString(Instrumentation.REPORT_KEY_IDENTIFIER,
        FailTestOnLeakRunListener.class.getName());
    bundle.putString(InstrumentationResultPrinter.REPORT_KEY_NAME_CLASS, testClass);
    bundle.putString(InstrumentationResultPrinter.REPORT_KEY_NAME_TEST, testName);
  }

  /**
   * Can be overridden to skip leak detection based on the description provided when a test
   * is started. Returns null to continue leak detection, or a string describing the reason for
   * skipping otherwise.
   */
  protected @Nullable String skipLeakDetectionReason(@NonNull Description description) {
    return null;
  }

  @Override public final void testFailure(Failure failure) {
    skipLeakDetectionReason = "failed";
  }

  @Override public final void testIgnored(Description description) {
    skipLeakDetectionReason = "was ignored";
  }

  @Override public final void testAssumptionFailure(Failure failure) {
    skipLeakDetectionReason = "had an assumption failure";
  }

  @Override public final void testFinished(Description description) {
    detectLeaks();
    LeakCanary.installedRefWatcher().clearWatchedReferences();
  }

  @Override public final void testRunStarted(Description description) {
  }

  @Override public final void testRunFinished(Result result) {
  }

  private void detectLeaks() {
    if (skipLeakDetectionReason != null) {
      CanaryLog.d("Skipping leak detection because the test %s", skipLeakDetectionReason);
      skipLeakDetectionReason = null;
      return;
    }

    InstrumentationLeakDetector leakDetector = new InstrumentationLeakDetector();
    InstrumentationLeakResults results = leakDetector.detectLeaks();

    reportLeaks(results);
  }

  /** Can be overridden to report leaks in a different way or do additional reporting. */
  protected void reportLeaks(@NonNull InstrumentationLeakResults results) {
    if (!results.detectedLeaks.isEmpty()) {
      String message =
          checkNotNull(buildLeakDetectedMessage(results.detectedLeaks), "buildLeakDetectedMessage");

      bundle.putString(InstrumentationResultPrinter.REPORT_KEY_STACK, message);
      getInstrumentation().sendStatus(REPORT_VALUE_RESULT_FAILURE, bundle);
    }
  }

  /** Can be overridden to customize the failure string message. */
  protected @NonNull String buildLeakDetectedMessage(
      @NonNull List<InstrumentationLeakResults.Result> detectedLeaks) {
    StringBuilder failureMessage = new StringBuilder();
    failureMessage.append(
        "Test failed because memory leaks were detected, see leak traces below.\n");
    failureMessage.append(SEPARATOR);

    Context context = getInstrumentation().getContext();
    for (InstrumentationLeakResults.Result detectedLeak : detectedLeaks) {
      failureMessage.append(
          LeakCanary.leakInfo(context, detectedLeak.heapDump, detectedLeak.analysisResult, true));
      failureMessage.append(SEPARATOR);
    }

    return failureMessage.toString();
  }
}
