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
import leakcanary.AppWatcher
import leakcanary.ObjectWatcher
import leakcanary.internal.InternalAppWatcher.noOpDelegate

/**
 * Internal class used to watch for fragments leaks.
 */
internal object FragmentDestroyWatcher {

  private const val ANDROIDX_FRAGMENT_CLASS_NAME = "androidx.fragment.app.Fragment"
  private const val ANDROIDX_FRAGMENT_DESTROY_WATCHER_CLASS_NAME =
    "leakcanary.internal.AndroidXFragmentDestroyWatcher"

  fun install(
    application: Application,
    objectWatcher: ObjectWatcher,
    configProvider: () -> AppWatcher.Config
  ) {
    val fragmentDestroyWatchers = mutableListOf<(Activity) -> Unit>()

    if (SDK_INT >= O) {
      fragmentDestroyWatchers.add(
          AndroidOFragmentDestroyWatcher(objectWatcher, configProvider)
      )
    }

    if (classAvailable(ANDROIDX_FRAGMENT_CLASS_NAME) &&
        classAvailable(ANDROIDX_FRAGMENT_DESTROY_WATCHER_CLASS_NAME)
    ) {
      val watcherConstructor = Class.forName(ANDROIDX_FRAGMENT_DESTROY_WATCHER_CLASS_NAME)
          .getDeclaredConstructor(ObjectWatcher::class.java, Function0::class.java)
      @kotlin.Suppress("UNCHECKED_CAST")
      fragmentDestroyWatchers.add(
          watcherConstructor.newInstance(objectWatcher, configProvider) as (Activity) -> Unit
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
          watcher(activity)
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
