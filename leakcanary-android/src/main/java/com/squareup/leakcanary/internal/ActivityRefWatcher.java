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

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import com.squareup.leakcanary.RefWatcher;

/**
 * Internal class used to watch for activity leaks.
 */
public final class ActivityRefWatcher {

  public static void install(Context context, RefWatcher refWatcher) {
    ActivityRefWatcher activityRefWatcher = new ActivityRefWatcher(refWatcher);

    Application application = (Application) context.getApplicationContext();
    application.registerActivityLifecycleCallbacks(activityRefWatcher.lifecycleCallbacks);
  }

  private final Application.ActivityLifecycleCallbacks lifecycleCallbacks =
      new ActivityLifecycleCallbacksAdapter() {
        @Override public void onActivityDestroyed(Activity activity) {
          refWatcher.watch(activity);
        }
      };

  private final RefWatcher refWatcher;

  private ActivityRefWatcher(RefWatcher refWatcher) {
    this.refWatcher = refWatcher;
  }
}
