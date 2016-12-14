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

import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;

import static com.squareup.leakcanary.Preconditions.checkNotNull;

public final class FragmentRefWatcher {

  public static void install(FragmentActivity activity, RefWatcher refWatcher) {
    FragmentRefWatcher fragmentRefWatcher = new FragmentRefWatcher(activity, refWatcher);
    fragmentRefWatcher.watchFragments();
  }

  private final FragmentManager.FragmentLifecycleCallbacks lifecycleCallbacks;

  private final FragmentManager fragmentManager;
  private final RefWatcher refWatcher;

  /**
   * Constructs an {@link FragmentRefWatcher} that will make sure the fragments are not leaking
   * after they have been destroyed.
   */
  public FragmentRefWatcher(FragmentActivity activity, final RefWatcher refWatcher) {
    this.fragmentManager = checkNotNull(activity, "activity").getSupportFragmentManager();
    this.refWatcher = checkNotNull(refWatcher, "refWatcher");

    this.lifecycleCallbacks = this.fragmentManager.new FragmentLifecycleCallbacks() {
      @Override public void onFragmentDestroyed(FragmentManager fm, Fragment fragment) {
        FragmentRefWatcher.this.onFragmentDestroyed(fragment);
      }
    };
  }

  void onFragmentDestroyed(Fragment fragment) {
    refWatcher.watch(fragment);
  }

  public void watchFragments() {
    // Make sure you don't get installed twice.
    stopWatchingFragments();
    fragmentManager.registerFragmentLifecycleCallbacks(lifecycleCallbacks, true);
  }

  public void stopWatchingFragments() {
    fragmentManager.unregisterFragmentLifecycleCallbacks(lifecycleCallbacks);
  }
}
