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

import android.app.Activity;
import android.app.Application;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentActivity;

import static com.squareup.leakcanary.Preconditions.checkNotNull;

public final class ActivityRefWatcher {

  /** @deprecated Use {@link #install(Application, RefWatcher)}. */
  @Deprecated
  public static void installOnIcsPlus(Application application, RefWatcher refWatcher) {
    install(application, refWatcher);
  }

  public static void install(Application application, RefWatcher refWatcher) {
    new ActivityRefWatcher(application, refWatcher).watchActivities();
  }

  private final Application.ActivityLifecycleCallbacks lifecycleCallbacks =
      new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
          if (activity instanceof FragmentActivity) {
            supportFragmentRefWatcher.watch((FragmentActivity) activity);
          }
          // FragmentActivity can still have non-support child fragments.
          if (fragmentRefWatcher != null) {
            fragmentRefWatcher.watch(activity);
          }
        }

        @Override public void onActivityStarted(Activity activity) {
        }

        @Override public void onActivityResumed(Activity activity) {
        }

        @Override public void onActivityPaused(Activity activity) {
        }

        @Override public void onActivityStopped(Activity activity) {
        }

        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override public void onActivityDestroyed(Activity activity) {
          ActivityRefWatcher.this.onActivityDestroyed(activity);
          if (activity instanceof FragmentActivity) {
            supportFragmentRefWatcher.unwatch((FragmentActivity) activity);
          }
          if (fragmentRefWatcher != null) {
            fragmentRefWatcher.unwatch(activity);
          }
        }
      };


  private final Application application;
  private final RefWatcher refWatcher;
  @Nullable private final FragmentRefWatcher fragmentRefWatcher;
  private final SupportFragmentRefWatcher supportFragmentRefWatcher;

  /**
   * Constructs an {@link ActivityRefWatcher} that will make sure the activities are not leaking
   * after they have been destroyed.
   */
  public ActivityRefWatcher(Application application, RefWatcher refWatcher) {
    this.application = checkNotNull(application, "application");
    this.refWatcher = checkNotNull(refWatcher, "refWatcher");
    this.supportFragmentRefWatcher = new SupportFragmentRefWatcher(refWatcher);
    if (VERSION.SDK_INT >= VERSION_CODES.O) {
      this.fragmentRefWatcher = new FragmentRefWatcher(refWatcher);
    } else {
      this.fragmentRefWatcher = null;
    }
  }

  void onActivityDestroyed(Activity activity) {
    refWatcher.watch(activity);
  }

  public void watchActivities() {
    // Make sure you don't get installed twice.
    stopWatchingActivities();
    application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
  }

  public void stopWatchingActivities() {
    application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
  }
}
