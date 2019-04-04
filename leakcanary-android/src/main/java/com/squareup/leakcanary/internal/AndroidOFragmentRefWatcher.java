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

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.os.Build;
import android.view.View;
import androidx.annotation.RequiresApi;
import com.squareup.leakcanary.LeakCanary;
import com.squareup.leakcanary.RefWatcher;
import kotlin.jvm.functions.Function0;

@RequiresApi(Build.VERSION_CODES.O) //
class AndroidOFragmentRefWatcher implements FragmentRefWatcher {

  private final RefWatcher refWatcher;
  private final Function0<LeakCanary.Config> configProvider;

  AndroidOFragmentRefWatcher(RefWatcher refWatcher, Function0<LeakCanary.Config> configProvider) {
    this.refWatcher = refWatcher;
    this.configProvider = configProvider;
  }

  private final FragmentManager.FragmentLifecycleCallbacks fragmentLifecycleCallbacks =
      new FragmentManager.FragmentLifecycleCallbacks() {

        @Override public void onFragmentViewDestroyed(FragmentManager fm, Fragment fragment) {
          View view = fragment.getView();
          if (view != null && configProvider.invoke().getWatchFragmentViews()) {
            refWatcher.watch(view);
          }
        }

        @Override
        public void onFragmentDestroyed(FragmentManager fm, Fragment fragment) {
          if (configProvider.invoke().getWatchFragments()) {
            refWatcher.watch(fragment);
          }
        }
      };

  @Override public void watchFragments(Activity activity) {
    FragmentManager fragmentManager = activity.getFragmentManager();
    fragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, true);
  }
}
