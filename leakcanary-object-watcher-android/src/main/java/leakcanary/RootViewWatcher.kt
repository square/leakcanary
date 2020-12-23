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
package leakcanary

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import android.view.View.OnAttachStateChangeListener
import leakcanary.internal.friendly.mainHandler
import shark.SharkLog

/**
 * Expects root views to become weakly reachable soon after they are removed from the window
 * manager.
 */
class RootViewWatcher(
  private val reachabilityWatcher: ReachabilityWatcher
) : InstallableWatcher {

  override fun install() {
    if (Build.VERSION.SDK_INT < 19) {
      return
    }
    swapViewManagerGlobalMViews { mViews ->
      object : ArrayList<View>(mViews) {
        override fun add(element: View): Boolean {
          onRootViewAdded(element)
          return super.add(element)
        }
      }
    }
  }

  override fun uninstall() {
    if (Build.VERSION.SDK_INT < 19) {
      return
    }
    swapViewManagerGlobalMViews { mViews ->
      ArrayList(mViews)
    }
  }

  @SuppressLint("PrivateApi")
  @Suppress("FunctionName")
  private fun swapViewManagerGlobalMViews(swap: (ArrayList<View>) -> ArrayList<View>) {
    try {
      val windowManagerGlobalClass = Class.forName("android.view.WindowManagerGlobal")
      val windowManagerGlobalInstance =
        windowManagerGlobalClass.getDeclaredMethod("getInstance").invoke(null)

      val mViewsField =
        windowManagerGlobalClass.getDeclaredField("mViews").apply { isAccessible = true }

      @Suppress("UNCHECKED_CAST")
      val mViews = mViewsField[windowManagerGlobalInstance] as ArrayList<View>

      mViewsField[windowManagerGlobalInstance] = swap(mViews)
    } catch (ignored: Throwable) {
      SharkLog.d(ignored) { "Could not watch detached root views" }
    }
  }

  private fun onRootViewAdded(rootView: View) {
    rootView.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {

      val watchDetachedView = Runnable {
        reachabilityWatcher.expectWeaklyReachable(
          rootView, "${rootView::class.java.name} received View#onDetachedFromWindow() callback"
        )
      }

      override fun onViewAttachedToWindow(v: View) {
        mainHandler.removeCallbacks(watchDetachedView)
      }

      override fun onViewDetachedFromWindow(v: View) {
        mainHandler.post(watchDetachedView)
      }
    })
  }
}
