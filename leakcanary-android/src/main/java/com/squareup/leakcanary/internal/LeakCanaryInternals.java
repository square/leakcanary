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

import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.content.pm.PackageManager.GET_SERVICES;
import static android.os.Environment.DIRECTORY_DOWNLOADS;

public final class LeakCanaryInternals {

  // SDK INT for API 22.
  public static final int LOLLIPOP_MR1 = 22;
  public static final String SAMSUNG = "samsung";
  public static final String MOTOROLA = "motorola";
  public static final String LG = "LGE";
  public static final String NVIDIA = "NVIDIA";

  private static final Executor fileIoExecutor = Executors.newSingleThreadExecutor();

  public static void executeOnFileIoThread(Runnable runnable) {
    fileIoExecutor.execute(runnable);
  }

  public static File storageDirectory() {
    File downloadsDirectory = Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS);
    File leakCanaryDirectory = new File(downloadsDirectory, "leakcanary");
    leakCanaryDirectory.mkdirs();
    return leakCanaryDirectory;
  }

  public static File detectedLeakDirectory() {
    File directory = new File(storageDirectory(), "detected_leaks");
    directory.mkdirs();
    return directory;
  }

  public static File leakResultFile(File heapdumpFile) {
    return new File(heapdumpFile.getParentFile(), heapdumpFile.getName() + ".result");
  }

  public static boolean isExternalStorageWritable() {
    String state = Environment.getExternalStorageState();
    return Environment.MEDIA_MOUNTED.equals(state);
  }

  public static File findNextAvailableHprofFile(int maxFiles) {
    File directory = detectedLeakDirectory();
    for (int i = 0; i < maxFiles; i++) {
      String heapDumpName = "heap_dump_" + i + ".hprof";
      File file = new File(directory, heapDumpName);
      if (!file.exists()) {
        return file;
      }
    }
    return null;
  }

  /** Extracts the class simple name out of a string containing a fully qualified class name. */
  public static String classSimpleName(String className) {
    int separator = className.lastIndexOf('.');
    if (separator == -1) {
      return className;
    } else {
      return className.substring(separator + 1);
    }
  }

  public static void setEnabled(Context context, final Class<?> componentClass,
      final boolean enabled) {
    final Context appContext = context.getApplicationContext();
    executeOnFileIoThread(new Runnable() {
      @Override public void run() {
        ComponentName component = new ComponentName(appContext, componentClass);
        PackageManager packageManager = appContext.getPackageManager();
        int newState = enabled ? COMPONENT_ENABLED_STATE_ENABLED : COMPONENT_ENABLED_STATE_DISABLED;
        // Blocks on IPC.
        packageManager.setComponentEnabledSetting(component, newState, DONT_KILL_APP);
      }
    });
  }

  public static boolean isInServiceProcess(Context context, Class<? extends Service> serviceClass) {
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

  private LeakCanaryInternals() {
    throw new AssertionError();
  }
}
