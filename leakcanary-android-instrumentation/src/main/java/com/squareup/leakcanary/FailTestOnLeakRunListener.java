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
import android.support.test.internal.runner.listener.InstrumentationResultPrinter;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import static android.support.test.InstrumentationRegistry.getInstrumentation;
import static android.support.test.internal.runner.listener.InstrumentationResultPrinter.REPORT_VALUE_RESULT_FAILURE;

/**
 * <p>A JUnit {@link RunListener} for detecting memory leaks in Android instrumentation tests. It
 * waits for the end of a test, and if the test succeeds then it will look for leaking
 * references, trigger a heap dump if needed and perform an analysis.
 *
 * @see InstrumentationLeakDetector
 */
public final class FailTestOnLeakRunListener extends RunListener {

  private static final String SEPARATOR = "######################################\n";
  private Bundle bundle;

  private boolean skipLeakDetection;

  @Override public void testStarted(Description description) {
    String testClass = description.getClassName();
    String testName = description.getMethodName();

    bundle = new Bundle();
    bundle.putString(Instrumentation.REPORT_KEY_IDENTIFIER,
        FailTestOnLeakRunListener.class.getName());
    bundle.putString(InstrumentationResultPrinter.REPORT_KEY_NAME_CLASS, testClass);
    bundle.putString(InstrumentationResultPrinter.REPORT_KEY_NAME_TEST, testName);
  }

  @Override public void testFailure(Failure failure) {
    skipLeakDetection = true;
  }

  @Override public void testIgnored(Description description) {
    skipLeakDetection = true;
  }

  @Override public void testAssumptionFailure(Failure failure) {
    skipLeakDetection = true;
  }

  @Override public void testFinished(Description description) {
    detectLeaks();
    LeakCanary.installedRefWatcher().clearWatchedReferences();
  }

  private void detectLeaks() {
    if (skipLeakDetection) {
      CanaryLog.d("Skipping leak detection");
      return;
    }

    InstrumentationLeakDetector leakDetector = new InstrumentationLeakDetector();
    InstrumentationLeakResults results = leakDetector.detectLeaks();

    if (results.detectedLeaks.isEmpty()) {
      return;
    }

    StringBuilder failureString = new StringBuilder();
    failureString.append(
        "Test failed because memory leaks were detected, see leak traces below.\n");
    failureString.append(SEPARATOR);

    Context context = getInstrumentation().getContext();
    for (InstrumentationLeakResults.Result detectedLeak : results.detectedLeaks) {
      failureString.append(
          LeakCanary.leakInfo(context, detectedLeak.heapDump, detectedLeak.analysisResult, true));
      failureString.append(SEPARATOR);
    }

    bundle.putString(InstrumentationResultPrinter.REPORT_KEY_STACK, failureString.toString());
    getInstrumentation().sendStatus(REPORT_VALUE_RESULT_FAILURE, bundle);
  }
}
