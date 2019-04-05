/*
 * Copyright (C) 2016 Square, Inc.
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
package com.squareup.leakcanary

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.M
import android.os.Environment
import android.os.Environment.DIRECTORY_DOWNLOADS
import com.squareup.leakcanary.HeapDumper.Companion.RETRY_LATER
import com.squareup.leakcanary.internal.LeakCanaryInternals
import com.squareup.leakcanary.internal.RequestStoragePermissionActivity
import java.io.File
import java.io.FilenameFilter
import java.util.ArrayList
import java.util.Arrays
import java.util.UUID

class DefaultLeakDirectoryProvider @JvmOverloads constructor(
  context: Context,
  private val maxStoredHeapDumps: Int = DEFAULT_MAX_STORED_HEAP_DUMPS
) : LeakDirectoryProvider {

  private val context: Context

  @Volatile private var writeExternalStorageGranted: Boolean = false
  @Volatile private var permissionNotificationDisplayed: Boolean = false

  init {
    if (maxStoredHeapDumps < 1) {
      throw IllegalArgumentException("maxStoredHeapDumps must be at least 1")
    }
    this.context = context.applicationContext
  }

  override fun listFiles(filter: FilenameFilter): MutableList<File> {
    if (!hasStoragePermission()) {
      requestWritePermissionNotification()
    }
    val files = ArrayList<File>()

    val externalFiles = externalStorageDirectory().listFiles(filter)
    if (externalFiles != null) {
      files.addAll(Arrays.asList(*externalFiles))
    }

    val appFiles = appStorageDirectory().listFiles(filter)
    if (appFiles != null) {
      files.addAll(Arrays.asList(*appFiles))
    }
    return files
  }

  override fun hasPendingHeapDump(): Boolean {
    val pendingHeapDumps =
      listFiles(FilenameFilter { _, filename -> filename.endsWith(PENDING_HEAPDUMP_SUFFIX) })
    for (file in pendingHeapDumps) {
      if (System.currentTimeMillis() - file.lastModified() < ANALYSIS_MAX_DURATION_MS) {
        return true
      }
    }
    return false
  }

  override fun newHeapDumpFile(): File? {
    val pendingHeapDumps =
      listFiles(FilenameFilter { _, filename -> filename.endsWith(PENDING_HEAPDUMP_SUFFIX) })

    // If a new heap dump file has been created recently and hasn't been processed yet, we skip.
    // Otherwise we move forward and assume that the analyzer process crashes. The file will
    // eventually be removed with heap dump file rotation.
    for (file in pendingHeapDumps) {
      if (System.currentTimeMillis() - file.lastModified() < ANALYSIS_MAX_DURATION_MS) {
        CanaryLog.d("Could not dump heap, previous analysis still is in progress.")
        return RETRY_LATER
      }
    }

    cleanupOldHeapDumps()

    var storageDirectory = externalStorageDirectory()
    if (!directoryWritableAfterMkdirs(storageDirectory)) {
      if (!hasStoragePermission()) {
        CanaryLog.d("WRITE_EXTERNAL_STORAGE permission not granted")
        requestWritePermissionNotification()
      } else {
        val state = Environment.getExternalStorageState()
        if (Environment.MEDIA_MOUNTED != state) {
          CanaryLog.d("External storage not mounted, state: %s", state)
        } else {
          CanaryLog.d(
              "Could not create heap dump directory in external storage: [%s]",
              storageDirectory.absolutePath
          )
        }
      }
      // Fallback to app storage.
      storageDirectory = appStorageDirectory()
      if (!directoryWritableAfterMkdirs(storageDirectory)) {
        CanaryLog.d(
            "Could not create heap dump directory in app storage: [%s]",
            storageDirectory.absolutePath
        )
        return RETRY_LATER
      }
    }
    // If two processes from the same app get to this step at the same time, they could both
    // create a heap dump. This is an edge case we ignore.
    return File(storageDirectory, UUID.randomUUID().toString() + PENDING_HEAPDUMP_SUFFIX)
  }

  override fun clearLeakDirectory() {
    val allFilesExceptPending =
      listFiles(FilenameFilter { _, filename -> !filename.endsWith(PENDING_HEAPDUMP_SUFFIX) })
    for (file in allFilesExceptPending) {
      val deleted = file.delete()
      if (!deleted) {
        CanaryLog.d("Could not delete file %s", file.path)
      }
    }
  }

  @TargetApi(M) private fun hasStoragePermission(): Boolean {
    if (SDK_INT < M) {
      return true
    }
    // Once true, this won't change for the life of the process so we can cache it.
    if (writeExternalStorageGranted) {
      return true
    }
    writeExternalStorageGranted =
      context.checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED
    return writeExternalStorageGranted
  }

  private fun requestWritePermissionNotification() {
    if (permissionNotificationDisplayed) {
      return
    }
    permissionNotificationDisplayed = true

    val pendingIntent = RequestStoragePermissionActivity.createPendingIntent(context)
    val contentTitle = context.getString(R.string.leak_canary_permission_notification_title)
    val packageName = context.packageName
    val contentText =
      context.getString(R.string.leak_canary_permission_notification_text, packageName)
    LeakCanaryInternals.showNotification(
        context, contentTitle, contentText, pendingIntent, -0x21504111
    )
  }

  private fun externalStorageDirectory(): File {
    val downloadsDirectory = Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)
    return File(downloadsDirectory, "leakcanary-" + context.packageName)
  }

  private fun appStorageDirectory(): File {
    val appFilesDirectory = context.filesDir
    return File(appFilesDirectory, "leakcanary")
  }

  private fun directoryWritableAfterMkdirs(directory: File): Boolean {
    val success = directory.mkdirs()
    return (success || directory.exists()) && directory.canWrite()
  }

  private fun cleanupOldHeapDumps() {
    val hprofFiles = listFiles(FilenameFilter { _, name -> name.endsWith(HPROF_SUFFIX) })
    val filesToRemove = hprofFiles.size - maxStoredHeapDumps
    if (filesToRemove > 0) {
      CanaryLog.d("Removing %d heap dumps", filesToRemove)
      // Sort with oldest modified first.
      hprofFiles.sortWith(Comparator { lhs, rhs ->
        java.lang.Long.valueOf(lhs.lastModified())
            .compareTo(rhs.lastModified())
      })
      for (i in 0 until filesToRemove) {
        val deleted = hprofFiles[i].delete()
        if (!deleted) {
          CanaryLog.d("Could not delete old hprof file %s", hprofFiles[i].path)
        }
      }
    }
  }

  companion object {

    private const val DEFAULT_MAX_STORED_HEAP_DUMPS = 7

    private const val HPROF_SUFFIX = ".hprof"
    private const val PENDING_HEAPDUMP_SUFFIX = "_pending$HPROF_SUFFIX"

    /** 10 minutes  */
    private const val ANALYSIS_MAX_DURATION_MS = 10 * 60 * 1000
  }
}
