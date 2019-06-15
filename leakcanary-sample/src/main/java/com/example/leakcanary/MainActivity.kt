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
package com.example.leakcanary

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import leakcanary.LeakSentry

class MainActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val container = object : FrameLayout(this) {
      override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d("MainActivity", "Container attached")
        // Calling removeAllViews() on the parent synchronously triggers a
        // call to onDetachedFromWindow() on this FrameLayout and
        // recursively its children (which haven't been attached yet).
        // Then as soon as this method (onAttachedToWindow()) is exited
        // then ViewGroup.dispatchAttachedToWindow() dispatches to its
        // children (the button). As a result, the button is receiving its
        // onDetachedFromWindow() first and then its onAttachedToWindow().
        //
        // On Android Q Beta this creates a leak because
        // android.view.View#onAttachedToWindow calls
        // AccessibilityNodeIdManager.registerViewWithId(); and then that
        // view is never detached.
        (parent as ViewGroup).removeAllViews()
      }

      override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d("MainActivity", "Container detached")
        // This tells LeakCanary to ensure the button gets GCed within 5
        // seconds.
        // Which it won't, ever, because at this point it's held forever
        // by AccessibilityNodeIdManager
        LeakSentry.refWatcher.watch(getChildAt(0))
      }
    }

    container.addView(object : Button(this) {
      override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d("MainActivity", "Button attached")
      }

      override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d("MainActivity", "Button detached")
      }
    })

    setContentView(container)
  }
}
