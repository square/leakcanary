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
import android.os.SystemClock
import android.view.View
import android.widget.Button
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class MainActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.main_activity)

    val app = application as ExampleApplication
    val leakedView = findViewById<View>(R.id.helper_text)

    findViewById<Button>(R.id.recreate_activity_button).setOnClickListener { recreate() }

    when (Random.nextInt(4)) {
      // Leak from application class
      0 -> app.leakedViews.add(leakedView)
      // Leak from Kotlin object singleton
      1 -> LeakingSingleton.leakedViews.add(leakedView)
      2 -> {
        // Leak from local variable on thread
        val ref = AtomicReference(this)
        val thread = Thread {
          val activity = ref.get()
          ref.set(null)
          while (true) {
            print(activity)
            SystemClock.sleep(1000)
          }
        }
        thread.name = "Leaking local variables"
        thread.start()
      }
      // Leak from thread fields
      else -> LeakingThread.thread.leakedViews.add(leakedView)
    }
  }
}
