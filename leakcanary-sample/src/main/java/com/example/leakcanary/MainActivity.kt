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

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View

class MainActivity : Activity() {

  private var httpRequestHelper: HttpRequestHelper? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.main_activity)

    val button = findViewById<View>(R.id.async_work)
    button.setOnClickListener { startAsyncWork() }

    httpRequestHelper = lastNonConfigurationInstance as HttpRequestHelper?
    if (httpRequestHelper == null) {
      httpRequestHelper = HttpRequestHelper(button)
    }
  }

  override fun onRetainNonConfigurationInstance(): Any? {
    return httpRequestHelper
  }

  @SuppressLint("StaticFieldLeak")
  internal fun startAsyncWork() {
    // This runnable is an anonymous class and therefore has a hidden reference to the outer
    // class MainActivity. If the activity gets destroyed before the thread finishes (e.g. rotation),
    // the activity instance will leak.
    val work = Runnable {
      // Do some slow work in background
      SystemClock.sleep(20000)
      Log.d("MainActivity", "Leaking $this")
    }
    Thread(work).start()
  }
}
