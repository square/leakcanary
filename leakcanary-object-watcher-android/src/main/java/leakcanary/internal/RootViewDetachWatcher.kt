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
package leakcanary.internal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.OnAttachStateChangeListener
import leakcanary.AppWatcher.Config
import leakcanary.ObjectWatcher
import shark.SharkLog

internal class RootViewDetachWatcher private constructor(
  private val objectWatcher: ObjectWatcher,
  private val configProvider: () -> Config
) {

  private val uiHandler = Handler(Looper.getMainLooper())

  private fun onRootViewAdded(rootView: View) {
    rootView.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {

      val watchDetachedView = Runnable {
        if (configProvider().watchRootViews) {
          objectWatcher.watch(
            rootView, "${rootView::class.java.name} received View#onDetachedFromWindow() callback"
          )
        }
      }

      override fun onViewAttachedToWindow(v: View) {
        uiHandler.removeCallbacks(watchDetachedView)
      }

      override fun onViewDetachedFromWindow(v: View) {
        uiHandler.post(watchDetachedView)
      }
    })
  }

  companion object {
    @SuppressLint("PrivateApi")
    fun install(
      objectWatcher: ObjectWatcher,
      configProvider: () -> Config
    ) {
      val rootViewDetachWatcher =
        RootViewDetachWatcher(objectWatcher, configProvider)

      try {
        val windowManagerGlobalClass = Class.forName("android.view.WindowManagerGlobal")
        val windowManagerGlobalInstance =
          windowManagerGlobalClass.getDeclaredMethod("getInstance").invoke(null)

        val mViewsField =
          windowManagerGlobalClass.getDeclaredField("mViews").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val mViews = mViewsField[windowManagerGlobalInstance] as ArrayList<View>

        mViewsField[windowManagerGlobalInstance] = object : ArrayList<View>(mViews) {
          override fun add(element: View): Boolean {
            rootViewDetachWatcher.onRootViewAdded(element)
            return super.add(element)
          }
        }
      } catch (ignored: Throwable) {
        SharkLog.d(ignored) { "Could not watch detached root views" }
      }
    }
  }
}
