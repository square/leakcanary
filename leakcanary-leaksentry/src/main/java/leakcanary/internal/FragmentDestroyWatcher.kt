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
package leakcanary.internal

import android.app.Activity
import android.app.Application
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import android.os.Bundle
import leakcanary.LeakSentry
import leakcanary.RefWatcher
import leakcanary.internal.InternalHelper.noOpDelegate

/**
 * Internal class used to watch for fragments leaks.
 */
internal interface FragmentDestroyWatcher {

  fun watchFragments(activity: Activity)

  companion object {

    private const val SUPPORT_FRAGMENT_CLASS_NAME = "androidx.fragment.app.Fragment"

    fun install(
      application: Application,
      refWatcher: RefWatcher,
      configProvider: () -> LeakSentry.Config
    ) {
      val fragmentDestroyWatchers = mutableListOf<FragmentDestroyWatcher>()

      if (SDK_INT >= O) {
        fragmentDestroyWatchers.add(
            AndroidOFragmentDestroyWatcher(refWatcher, configProvider)
        )
      }

      if (classAvailable(
              SUPPORT_FRAGMENT_CLASS_NAME
          )
      ) {
        fragmentDestroyWatchers.add(
            SupportFragmentDestroyWatcher(refWatcher, configProvider)
        )
      }

      if (fragmentDestroyWatchers.size == 0) {
        return
      }

      application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks by noOpDelegate() {
        override fun onActivityCreated(
          activity: Activity,
          savedInstanceState: Bundle?
        ) {
          for (watcher in fragmentDestroyWatchers) {
            watcher.watchFragments(activity)
          }
        }
      })
    }

    private fun classAvailable(className: String): Boolean {
      return try {
        Class.forName(className)
        true
      } catch (e: ClassNotFoundException) {
        false
      }
    }
  }
}
