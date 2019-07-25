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

import android.app.Application
import android.os.Debug
import android.os.StrictMode
import android.os.SystemClock
import android.view.View
import leakcanary.AppWatcher
import leakcanary.GcTrigger
import leakcanary.KeyedWeakReference
import leakcanary.OnObjectRetainedListener
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import shark.HeapAnalyzer
import shark.OnAnalysisProgressListener
import shark.SharkLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class ExampleApplication : Application() {
  val leakedViews = mutableListOf<View>()

  override fun onCreate() {
    super.onCreate()
    enabledStrictMode()
    if (!BuildConfig.DEBUG) {
      watchForLeaksInReleaseBuilds()
    }
  }

  private fun watchForLeaksInReleaseBuilds() {
    AppWatcher.config = AppWatcher.config.copy(enabled = true)
    AppWatcher.objectWatcher.addOnObjectRetainedListener(OnObjectRetainedListener {
      SharkLog.d("Received object retained")
      GcTrigger.Default.runGc()
      if (AppWatcher.objectWatcher.retainedObjectCount == 0) {
        return@OnObjectRetainedListener
      }
      SharkLog.d("Dumping the heap")
      val heapDumpUptimeMillis = SystemClock.uptimeMillis()
      val directory = File(filesDir, "leakcanary")
      val success = (directory.mkdirs() || directory.exists()) && directory.canWrite()
      if (!success) {
        SharkLog.d(
            "Could not create heap dump directory in app storage: [%s]", directory.absolutePath
        )
        return@OnObjectRetainedListener
      }
      val fileName = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'.hprof'", Locale.US).format(
          Date()
      )
      val heapDumpFile = File(directory, fileName)

      KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis
      try {
        Debug.dumpHprofData(heapDumpFile.absolutePath)
        if (heapDumpFile.length() == 0L) {
          SharkLog.d("Dumped heap file is 0 byte length")
          return@OnObjectRetainedListener
        }
      } catch (e: Exception) {
        SharkLog.d(e, "Could not dump heap")
        // Abort heap dump
        return@OnObjectRetainedListener
      }

      if (!heapDumpFile.exists()) {
        SharkLog.d("Hprof file missing $heapDumpFile")
        return@OnObjectRetainedListener
      }
      val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)

      val heapAnalysis =
        heapAnalyzer.checkForLeaks(
            heapDumpFile, AndroidReferenceMatchers.appDefaults, true,
            AndroidObjectInspectors.appDefaults
        )
      SharkLog.d(heapAnalysis.toString())
    })
  }

  private fun enabledStrictMode() {
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectAll()
            .penaltyLog()
            .penaltyDeath()
            .build()
    )
  }
}
