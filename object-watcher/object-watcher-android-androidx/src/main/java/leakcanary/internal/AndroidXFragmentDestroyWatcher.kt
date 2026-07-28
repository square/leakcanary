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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import leakcanary.DeletableObjectReporter

internal class AndroidXFragmentDestroyWatcher(
  private val deletableObjectReporter: DeletableObjectReporter
) : (Activity) -> Unit {

  private val mainHandler = Handler(Looper.getMainLooper())

  private val fragmentLifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {

    override fun onFragmentCreated(
      fm: FragmentManager,
      fragment: Fragment,
      savedInstanceState: Bundle?
    ) {
      ViewModelClearedWatcher.install(fragment, deletableObjectReporter)
    }

    override fun onFragmentViewDestroyed(
      fm: FragmentManager,
      fragment: Fragment
    ) {
      val view = fragment.view
      if (view != null) {
        deletableObjectReporter.expectDeletionFor(
          view, "${fragment::class.java.name} received Fragment#onDestroyView() callback " +
          "(references to its views should be cleared to prevent leaks)"
        )
      }
    }

    override fun onFragmentDestroyed(
      fm: FragmentManager,
      fragment: Fragment
    ) {
      if (FRAGMENT_INSTANCES_CAN_BE_REUSED) {
        // From androidx.fragment 1.1.0 on, Fragment#initState() resets a destroyed fragment back to
        // a pristine state, which makes it legal to add that very same instance to a
        // FragmentManager again. Reused fragments would otherwise be reported as retained, so we
        // need to check whether this fragment is really on its way out. FragmentManager dispatches
        // onFragmentDestroyed() while it is still executing a transaction, and a fragment can be
        // removed and added back within a single transaction, so we wait until that transaction is
        // done before deciding. A fragment that is added back later still gets reported as
        // retained, however the heap analysis then finds it alive and says so in the leak trace.
        mainHandler.post {
          if (!fragment.isAdded) {
            expectDeletionFor(fragment)
          }
        }
      } else {
        expectDeletionFor(fragment)
      }
    }

    private fun expectDeletionFor(fragment: Fragment) {
      deletableObjectReporter.expectDeletionFor(
        fragment, "${fragment::class.java.name} received Fragment#onDestroy() callback"
      )
    }
  }

  override fun invoke(activity: Activity) {
    if (activity is FragmentActivity) {
      val supportFragmentManager = activity.supportFragmentManager
      supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, true)
      ViewModelClearedWatcher.install(activity, deletableObjectReporter)
    }
  }

  companion object {
    /**
     * androidx.fragment 1.1.0 changed `Fragment#initState()` to replace the fragment
     * `LifecycleRegistry`, resetting a destroyed fragment back to an `INITIALIZED` state so that the
     * very same instance can be added to a `FragmentManager` again. See `AndroidXFragmentVersion`
     * in shark-android, which detects the same change when analyzing a heap dump.
     *
     * `FragmentViewLifecycleOwner` was added by that same release. Unlike the heap analysis, which
     * can only see the classes that were loaded, we can look a class up by name here, and class
     * lookups are cheaper than reflecting on field declarations.
     *
     * If the lookup fails, for instance because the class was renamed when building a release
     * variant, we fall back to the behavior that was correct up to androidx.fragment 1.0.0, i.e. we
     * report every destroyed fragment.
     */
    private val FRAGMENT_INSTANCES_CAN_BE_REUSED: Boolean by lazy {
      try {
        Class.forName("androidx.fragment.app.FragmentViewLifecycleOwner")
        true
      } catch (ignored: Throwable) {
        false
      }
    }
  }
}
