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

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import com.squareup.leakcanary.internal.DisplayLeakActivity;
import com.squareup.leakcanary.internal.HeapAnalyzerService;

import static android.text.format.Formatter.formatShortFileSize;
import static com.squareup.leakcanary.BuildConfig.GIT_SHA;
import static com.squareup.leakcanary.BuildConfig.LIBRARY_VERSION;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.isInServiceProcess;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.setEnabled;

public final class LeakCanary {
  /**
   * Use custom resource provider.
   * @param provider
   */
  public static void installResourceProvider(IResourceProvider provider) {
    ResourceProvider.setProvider(provider);
  }

  /**
   * Creates a {@link RefWatcher} that works out of the box, and starts watching activity
   * references (on ICS+).
   */
  public static RefWatcher install(Application application) {
    return install(application, DisplayLeakService.class,
        AndroidExcludedRefs.createAppDefaults().build());
  }

  /**
   * Creates a {@link RefWatcher} that reports results to the provided service, and starts watching
   * activity references (on ICS+).
   */
  public static RefWatcher install(Application application,
      Class<? extends AbstractAnalysisResultService> listenerServiceClass,
      ExcludedRefs excludedRefs) {
    if (isInAnalyzerProcess(application)) {
      return RefWatcher.DISABLED;
    }
    enableDisplayLeakActivity(application);
    HeapDump.Listener heapDumpListener =
        new ServiceHeapDumpListener(application, listenerServiceClass);
    RefWatcher refWatcher = androidWatcher(application, heapDumpListener, excludedRefs);
    ActivityRefWatcher.installOnIcsPlus(application, refWatcher);
    return refWatcher;
  }

  /**
   * Creates a {@link RefWatcher} with a default configuration suitable for Android.
   */
  public static RefWatcher androidWatcher(Context context, HeapDump.Listener heapDumpListener,
      ExcludedRefs excludedRefs) {
    LeakDirectoryProvider leakDirectoryProvider = new DefaultLeakDirectoryProvider(context);
    DebuggerControl debuggerControl = new AndroidDebuggerControl();
    AndroidHeapDumper heapDumper = new AndroidHeapDumper(context, leakDirectoryProvider);
    heapDumper.cleanup();
    Resources resources = context.getResources();
    int watchDelayMillis = resources.getInteger(ResourceProvider.provider().leak_canary_watch_delay_millis());
    AndroidWatchExecutor executor = new AndroidWatchExecutor(watchDelayMillis);
    return new RefWatcher(executor, debuggerControl, GcTrigger.DEFAULT, heapDumper,
        heapDumpListener, excludedRefs);
  }

  public static void enableDisplayLeakActivity(Context context) {
    setEnabled(context, DisplayLeakActivity.class, true);
  }

  public static void setDisplayLeakActivityDirectoryProvider(
      LeakDirectoryProvider leakDirectoryProvider) {
    DisplayLeakActivity.setLeakDirectoryProvider(leakDirectoryProvider);
  }

  /** Returns a string representation of the result of a heap analysis. */
  public static String leakInfo(Context context, HeapDump heapDump, AnalysisResult result,
      boolean detailed) {
    PackageManager packageManager = context.getPackageManager();
    String packageName = context.getPackageName();
    PackageInfo packageInfo;
    try {
      packageInfo = packageManager.getPackageInfo(packageName, 0);
    } catch (PackageManager.NameNotFoundException e) {
      throw new RuntimeException(e);
    }
    String versionName = packageInfo.versionName;
    int versionCode = packageInfo.versionCode;
    String info = "In " + packageName + ":" + versionName + ":" + versionCode + ".\n";
    String detailedString = "";
    if (result.leakFound) {
      if (result.excludedLeak) {
        info += "* EXCLUDED LEAK.\n";
      }
      info += "* " + result.className;
      if (!heapDump.referenceName.equals("")) {
        info += " (" + heapDump.referenceName + ")";
      }
      info += " has leaked:\n" + result.leakTrace.toString() + "\n";
      info += "* Retaining: " + formatShortFileSize(context, result.retainedHeapSize) + ".\n";
      if (detailed) {
        detailedString = "\n* Details:\n" + result.leakTrace.toDetailedString();
      }
    } else if (result.failure != null) {
      // We duplicate the library version & Sha information because bug reports often only contain
      // the stacktrace.
      info += "* FAILURE in " + LIBRARY_VERSION + " " + GIT_SHA + ":" + Log.getStackTraceString(
          result.failure) + "\n";
    } else {
      info += "* NO LEAK FOUND.\n\n";
    }
    if (detailed) {
      detailedString += "* Excluded Refs:\n" + heapDump.excludedRefs;
    }

    info += "* Reference Key: "
        + heapDump.referenceKey
        + "\n"
        + "* Device: "
        + Build.MANUFACTURER
        + " "
        + Build.BRAND
        + " "
        + Build.MODEL
        + " "
        + Build.PRODUCT
        + "\n"
        + "* Android Version: "
        + Build.VERSION.RELEASE
        + " API: "
        + Build.VERSION.SDK_INT
        + " LeakCanary: "
        + LIBRARY_VERSION
        + " "
        + GIT_SHA
        + "\n"
        + "* Durations: watch="
        + heapDump.watchDurationMs
        + "ms, gc="
        + heapDump.gcDurationMs
        + "ms, heap dump="
        + heapDump.heapDumpDurationMs
        + "ms, analysis="
        + result.analysisDurationMs
        + "ms"
        + "\n"
        + detailedString;

    return info;
  }

  /**
   * Whether the current process is the process running the {@link HeapAnalyzerService}, which is
   * a different process than the normal app process.
   */
  public static boolean isInAnalyzerProcess(Context context) {
    return isInServiceProcess(context, HeapAnalyzerService.class);
  }

  private LeakCanary() {
    throw new AssertionError();
  }
}
