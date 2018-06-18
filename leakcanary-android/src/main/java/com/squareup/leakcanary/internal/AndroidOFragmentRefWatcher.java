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
package com.squareup.leakcanary.internal;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.os.Build;
import com.squareup.leakcanary.RefWatcher;

@TargetApi(Build.VERSION_CODES.O) //
class AndroidOFragmentRefWatcher implements FragmentRefWatcher {

  private final RefWatcher refWatcher;

  AndroidOFragmentRefWatcher(RefWatcher refWatcher) {
    this.refWatcher = refWatcher;
  }

  private final FragmentManager.FragmentLifecycleCallbacks fragmentLifecycleCallbacks =
      new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentDestroyed(FragmentManager fm, Fragment fragment) {
          refWatcher.watch(fragment);
        }
      };

  @Override public void watchFragments(Activity activity) {
    FragmentManager fragmentManager = activity.getFragmentManager();
    fragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, true);
  }

  @Override public void stopWatchingFragments(Activity activity) {
    FragmentManager fragmentManager = activity.getFragmentManager();
    fragmentManager.unregisterFragmentLifecycleCallbacks(fragmentLifecycleCallbacks);
  }
}
