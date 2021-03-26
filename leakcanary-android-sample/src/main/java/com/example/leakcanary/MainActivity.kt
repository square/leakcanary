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
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Handler
import android.os.StatFs
import android.os.SystemClock
import android.view.View
import android.widget.Button
import shark.SharkLog
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class MainActivity : Activity() {

  private var leakyReceiver = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.main_activity)

    val app = application as ExampleApplication
    findViewById<Button>(R.id.recreate_activity_button).setOnClickListener { recreate() }
    findViewById<Button>(R.id.leak_activity_button).setOnClickListener {
      val leakedView = findViewById<View>(R.id.helper_text)
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
    findViewById<Button>(R.id.show_dialog_button).setOnClickListener {
      AlertDialog.Builder(this)
        .setTitle("Leaky dialog")
        .setPositiveButton("Dismiss and leak dialog") { dialog, _ ->
          app.leakedDialogs += dialog as AlertDialog
        }
        .show()
    }
    findViewById<Button>(R.id.start_service_button).setOnClickListener {
      startService(Intent(this, LeakingService::class.java))
    }
    findViewById<Button>(R.id.leak_receiver_button).setOnClickListener {
      leakyReceiver = true
      recreate()
    }
  }

  class NoOpBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(
      context: Context,
      intent: Intent
    ) = Unit
  }

  override fun onDestroy() {
    super.onDestroy()
    if (leakyReceiver) {
      Handler().postDelayed({
        registerReceiver(NoOpBroadcastReceiver(), IntentFilter())
      }, 500)
    }
  }
}
