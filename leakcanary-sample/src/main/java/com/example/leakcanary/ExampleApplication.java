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
package com.example.leakcanary;

import android.app.Application;
import android.os.Build;
import android.os.StrictMode;
import com.squareup.leakcanary.LeakCanary;

public class ExampleApplication extends Application {
  @Override public void onCreate() {
    super.onCreate();
    setupLeakCanary();
  }

  protected void setupLeakCanary() {
    enabledStrictMode();
    if (LeakCanary.isInAnalyzerProcess(this)) {
      // This process is dedicated to LeakCanary for heap analysis.
      // You should not init your app in this process.
      return;
    }
    LeakCanary.install(this);
  }

  private static void enabledStrictMode() {
    StrictMode.ThreadPolicy.Builder builder = new StrictMode.ThreadPolicy.Builder();
    // Disabled DiskReadViolation, see https://github.com/square/leakcanary/issues/1222
    //    builder.detectDiskReads();
    builder.detectDiskWrites();
    builder.detectNetwork();
    builder.detectCustomSlowCalls();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      builder.detectResourceMismatches();
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      builder.detectUnbufferedIo();
    }
    builder.penaltyLog();
    builder.penaltyDeath();
    StrictMode.setThreadPolicy(builder.build());
  }
}
