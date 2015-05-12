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
import android.os.StrictMode;
import com.squareup.leakcanary.LeakCanary;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.GINGERBREAD;

public class ExampleApplication extends Application {

  @Override public void onCreate() {
    super.onCreate();
    enabledStrictMode();
    LeakCanary.install(this);
  }

  private void enabledStrictMode() {
    if (SDK_INT >= GINGERBREAD) {
      StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder() //
          .detectAll() //
          .penaltyLog() //
          .penaltyDeath() //
          .build());
    }
  }
}
