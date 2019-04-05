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
@file:Suppress("DEPRECATION")

package leakcanary.internal

import android.app.Activity
import android.app.Fragment
import android.app.FragmentManager
import android.os.Build
import androidx.annotation.RequiresApi
import leakcanary.RefWatcher
import leakcanary.LeakSentry.Config

@RequiresApi(Build.VERSION_CODES.O) //
internal class AndroidOFragmentDestroyWatcher(
  private val refWatcher: RefWatcher,
  private val configProvider: () -> Config
) : FragmentDestroyWatcher {

  private val fragmentLifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {

    override fun onFragmentViewDestroyed(
      fm: FragmentManager,
      fragment: Fragment
    ) {
      val view = fragment.view
      if (view != null && configProvider().watchFragmentViews) {
        refWatcher.watch(view)
      }
    }

    override fun onFragmentDestroyed(
      fm: FragmentManager,
      fragment: Fragment
    ) {
      if (configProvider().watchFragments) {
        refWatcher.watch(fragment)
      }
    }
  }

  override fun watchFragments(activity: Activity) {
    val fragmentManager = activity.fragmentManager
    fragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, true)
  }
}
