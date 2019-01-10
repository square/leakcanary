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
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Log;
import com.squareup.leakcanary.internal.DisplayLeakActivity;
import com.squareup.leakcanary.internal.HeapAnalyzerService;
import com.squareup.leakcanary.internal.LeakCanaryInternals;

import static android.text.format.Formatter.formatShortFileSize;
import static com.squareup.leakcanary.BuildConfig.GIT_SHA;
import static com.squareup.leakcanary.BuildConfig.LIBRARY_VERSION;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.isInServiceProcess;

public final class LeakCanary {

  /**
   * Creates a {@link RefWatcher} that works out of the box, and starts watching activity
   * references (on ICS+).
   */
  public static @NonNull RefWatcher install(@NonNull Application application) {
    return refWatcher(application).listenerServiceClass(DisplayLeakService.class)
        .excludedRefs(AndroidExcludedRefs.createAppDefaults().build())
        .buildAndInstall();
  }

  /**
   * Returns the {@link RefWatcher} installed via
   * {@link AndroidRefWatcherBuilder#buildAndInstall()}, and {@link RefWatcher#DISABLED} is no
   * {@link RefWatcher} has been installed.
   */
  public static @NonNull RefWatcher installedRefWatcher() {
    RefWatcher refWatcher = LeakCanaryInternals.installedRefWatcher;
    if (refWatcher == null) {
      return RefWatcher.DISABLED;
    }
    return refWatcher;
  }

  public static @NonNull AndroidRefWatcherBuilder refWatcher(@NonNull Context context) {
    return new AndroidRefWatcherBuilder(context);
  }

  /**
   * Blocking inter process call that enables the {@link DisplayLeakActivity}. When you first
   * install the app, {@link DisplayLeakActivity} is enabled by default if LeakCanary is configured
   * to use {@link DisplayLeakService}. You can call this method to enable
   * {@link DisplayLeakActivity} manually.
   */
  public static void enableDisplayLeakActivity(@NonNull Context context) {
    LeakCanaryInternals.setEnabledBlocking(context, DisplayLeakActivity.class, true);
  }

  /**
   * @deprecated Use {@link #setLeakDirectoryProvider(LeakDirectoryProvider)} instead.
   */
  @Deprecated
  public static void setDisplayLeakActivityDirectoryProvider(
      @NonNull LeakDirectoryProvider leakDirectoryProvider) {
    setLeakDirectoryProvider(leakDirectoryProvider);
  }

  /**
   * Used to customize the location for the storage of heap dumps. The default implementation is
   * {@link DefaultLeakDirectoryProvider}.
   *
   * @throws IllegalStateException if a LeakDirectoryProvider has already been set, including
   * if the default has been automatically set when installing the ref watcher.
   */
  public static void setLeakDirectoryProvider(
      @NonNull LeakDirectoryProvider leakDirectoryProvider) {
    LeakCanaryInternals.setLeakDirectoryProvider(leakDirectoryProvider);
  }

  /** Returns a string representation of the result of a heap analysis. */
  public static @NonNull String leakInfo(@NonNull Context context,
      @NonNull HeapDump heapDump,
      @NonNull AnalysisResult result,
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
      if (result.retainedHeapSize != AnalysisResult.RETAINED_HEAP_SKIPPED) {
        info += "* Retaining: " + formatShortFileSize(context, result.retainedHeapSize) + ".\n";
      }
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
  public static boolean isInAnalyzerProcess(@NonNull Context context) {
    Boolean isInAnalyzerProcess = LeakCanaryInternals.isInAnalyzerProcess;
    // This only needs to be computed once per process.
    if (isInAnalyzerProcess == null) {
      isInAnalyzerProcess = isInServiceProcess(context, HeapAnalyzerService.class);
      LeakCanaryInternals.isInAnalyzerProcess = isInAnalyzerProcess;
    }
    return isInAnalyzerProcess;
  }

  private LeakCanary() {
    throw new AssertionError();
  }
}
