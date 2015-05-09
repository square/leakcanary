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

import android.app.ActivityManager;
import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;
import com.squareup.leakcanary.internal.DisplayLeakActivity;
import com.squareup.leakcanary.internal.HeapAnalyzerService;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.content.pm.PackageManager.GET_SERVICES;

public final class LeakCanary {

    private static RefWatcher refWatcher;

    /**
   * Creates a {@link RefWatcher} that works out of the box, and starts watching activity
   * references (on ICS+).
   */
  public static RefWatcher install(Application application) {
    return install(application, DisplayLeakService.class);
  }

  /**
   * Creates a {@link RefWatcher} that reports results to the provided service, and starts watching
   * activity references (on ICS+).
   */
  public static RefWatcher install(Application application,
      Class<? extends AbstractAnalysisResultService> listenerServiceClass) {
    if (isInAnalyzerProcess(application)) {
      return RefWatcher.DISABLED;
    }
    enableDisplayLeakActivity(application);
    HeapDump.Listener heapDumpListener =
        new ServiceHeapDumpListener(application, listenerServiceClass);
      refWatcher = androidWatcher(application, heapDumpListener);
    ActivityRefWatcher.installOnIcsPlus(application, refWatcher);
    return refWatcher;
  }

  /**
   * Creates a {@link RefWatcher} with a default configuration suitable for Android.
   */
  public static RefWatcher androidWatcher(Application app, HeapDump.Listener heapDumpListener) {
    DebuggerControl debuggerControl = new AndroidDebuggerControl();
    AndroidHeapDumper heapDumper = new AndroidHeapDumper(app);
    heapDumper.cleanup();
    return new RefWatcher(new AndroidWatchExecutor(), debuggerControl, GcTrigger.DEFAULT,
        heapDumper, heapDumpListener);
  }

  public static void enableDisplayLeakActivity(Context context) {
    setEnabled(context, DisplayLeakActivity.class, true);
  }

  /** Returns a string representation of the result of a heap analysis. */
  public static String leakInfo(Context context, HeapDump heapDump, AnalysisResult result) {
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
    if (result.leakFound) {
      if (result.excludedLeak) {
        info += "* LEAK CAN BE IGNORED.\n";
      }
      info += "* " + result.className;
      if (!heapDump.referenceName.equals("")) {
        info += " (" + heapDump.referenceName + ")";
      }
      info += " has leaked:\n" + result.leakTrace.toString() + "\n";
    } else if (result.failure != null) {
      info += "* FAILURE:\n" + Log.getStackTraceString(result.failure) + "\n";
    } else {
      info += "* NO LEAK FOUND.\n\n";
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
        + "\n";

    return info;
  }

  public static void watch(final Object watchedReference, String referenceName) {
    if (refWatcher == null) {
      return;
    }
    refWatcher.watch(watchedReference, referenceName);
  }

  public static void watch(final Object watchedReference) {
    watch(watchedReference, "");
  }

  /**
   * Whether the current process is the process running the {@link HeapAnalyzerService}, which is
   * a different process than the normal app process.
   */
  public static boolean isInAnalyzerProcess(Context context) {
    return isInServiceProcess(context, HeapAnalyzerService.class);
  }

  private static boolean isInServiceProcess(Context context,
      Class<? extends Service> serviceClass) {
    PackageManager packageManager = context.getPackageManager();
    PackageInfo packageInfo;
    try {
      packageInfo = packageManager.getPackageInfo(context.getPackageName(), GET_SERVICES);
    } catch (Exception e) {
      Log.e("AndroidUtils", "Could not get package info for " + context.getPackageName(), e);
      return false;
    }
    String mainProcess = packageInfo.applicationInfo.processName;

    ComponentName component = new ComponentName(context, serviceClass);
    ServiceInfo serviceInfo;
    try {
      serviceInfo = packageManager.getServiceInfo(component, 0);
    } catch (PackageManager.NameNotFoundException ignored) {
      // Service is disabled.
      return false;
    }

    if (serviceInfo.processName.equals(mainProcess)) {
      Log.e("AndroidUtils",
          "Did not expect service " + serviceClass + " to run in main process " + mainProcess);
      // Technically we are in the service process, but we're not in the service dedicated process.
      return false;
    }

    int myPid = android.os.Process.myPid();
    ActivityManager activityManager =
        (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    ActivityManager.RunningAppProcessInfo myProcess = null;
    for (ActivityManager.RunningAppProcessInfo process : activityManager.getRunningAppProcesses()) {
      if (process.pid == myPid) {
        myProcess = process;
        break;
      }
    }
    if (myProcess == null) {
      Log.e("AndroidUtils", "Could not find running process for " + myPid);
      return false;
    }

    return myProcess.processName.equals(serviceInfo.processName);
  }

  static void setEnabled(Context context, Class<?> componentClass, boolean enabled) {
    ComponentName component = new ComponentName(context, componentClass);
    PackageManager packageManager = context.getPackageManager();
    int newState = enabled ? COMPONENT_ENABLED_STATE_ENABLED : COMPONENT_ENABLED_STATE_DISABLED;
    // Blocks on IPC.
    packageManager.setComponentEnabledSetting(component, newState, DONT_KILL_APP);
  }

  /** Extracts the class simple name out of a string containing a fully qualified class name. */
  static String classSimpleName(String className) {
    int separator = className.lastIndexOf('.');
    if (separator == -1) {
      return className;
    } else {
      return className.substring(separator + 1);
    }
  }

  private LeakCanary() {
    throw new AssertionError();
  }
}
