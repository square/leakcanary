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
package leakcanary.internal

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.M
import android.os.Environment
import android.os.Environment.DIRECTORY_DOWNLOADS
import com.squareup.leakcanary.core.R
import leakcanary.internal.NotificationType.LEAKCANARY_LOW
import shark.SharkLog
import java.io.File
import java.io.FilenameFilter
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

/**
 * Provides access to where heap dumps and analysis results will be stored.
 */
internal class LeakDirectoryProvider constructor(
  context: Context,
  private val maxStoredHeapDumps: () -> Int,
  private val requestExternalStoragePermission: () -> Boolean
) {
  private val context: Context = context.applicationContext

  fun listFiles(filter: FilenameFilter): MutableList<File> {
    if (!hasStoragePermission() && requestExternalStoragePermission()) {
      requestWritePermissionNotification()
    }
    val files = ArrayList<File>()

    val externalFiles = externalStorageDirectory().listFiles(filter)
    if (externalFiles != null) {
      files.addAll(externalFiles)
    }

    val appFiles = appStorageDirectory().listFiles(filter)
    if (appFiles != null) {
      files.addAll(appFiles)
    }
    return files
  }

  fun newHeapDumpFile(): File? {
    cleanupOldHeapDumps()

    var storageDirectory = externalStorageDirectory()
    if (!directoryWritableAfterMkdirs(storageDirectory)) {
      if (!hasStoragePermission()) {
        if (requestExternalStoragePermission()) {
          SharkLog.d { "WRITE_EXTERNAL_STORAGE permission not granted, requesting" }
          requestWritePermissionNotification()
        } else {
          SharkLog.d { "WRITE_EXTERNAL_STORAGE permission not granted, ignoring" }
        }
      } else {
        val state = Environment.getExternalStorageState()
        if (Environment.MEDIA_MOUNTED != state) {
          SharkLog.d { "External storage not mounted, state: $state" }
        } else {
          SharkLog.d {
              "Could not create heap dump directory in external storage: [${storageDirectory.absolutePath}]"
          }
        }
      }
      // Fallback to app storage.
      storageDirectory = appStorageDirectory()
      if (!directoryWritableAfterMkdirs(storageDirectory)) {
        SharkLog.d {
            "Could not create heap dump directory in app storage: [${storageDirectory.absolutePath}]"
        }
        return null
      }
    }

    val fileName = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'.hprof'", Locale.US).format(Date())
    return File(storageDirectory, fileName)
  }

  @TargetApi(M) fun hasStoragePermission(): Boolean {
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

  fun requestWritePermissionNotification() {
    if (permissionNotificationDisplayed) {
      return
    }
    permissionNotificationDisplayed = true

    val pendingIntent = RequestStoragePermissionActivity.createPendingIntent(context)
    val contentTitle = context.getString(
        R.string.leak_canary_permission_notification_title
    )
    val packageName = context.packageName
    val contentText =
      context.getString(R.string.leak_canary_permission_notification_text, packageName)

    Notifications.showNotification(
        context, contentTitle, contentText, pendingIntent,
        R.id.leak_canary_notification_write_permission, LEAKCANARY_LOW
    )
  }

  @Suppress("DEPRECATION")
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
    val hprofFiles = listFiles(FilenameFilter { _, name ->
      name.endsWith(
          HPROF_SUFFIX
      )
    })
    val maxStoredHeapDumps = maxStoredHeapDumps()
    if (maxStoredHeapDumps < 1) {
      throw IllegalArgumentException("maxStoredHeapDumps must be at least 1")
    }

    val filesToRemove = hprofFiles.size - maxStoredHeapDumps
    if (filesToRemove > 0) {
      SharkLog.d { "Removing $filesToRemove heap dumps" }
      // Sort with oldest modified first.
      hprofFiles.sortWith(Comparator { lhs, rhs ->
        java.lang.Long.valueOf(lhs.lastModified())
            .compareTo(rhs.lastModified())
      })
      for (i in 0 until filesToRemove) {
        val path = hprofFiles[i].absolutePath
        val deleted = hprofFiles[i].delete()
        if (deleted) {
          filesDeletedTooOld += path
        } else {
          SharkLog.d { "Could not delete old hprof file ${hprofFiles[i].path}" }
        }
      }
    }
  }

  companion object {
    @Volatile private var writeExternalStorageGranted: Boolean = false
    @Volatile private var permissionNotificationDisplayed: Boolean = false

    private val filesDeletedTooOld = mutableListOf<String>()
    val filesDeletedRemoveLeak = mutableListOf<String>()

    private const val HPROF_SUFFIX = ".hprof"

    fun hprofDeleteReason(file: File): String {
      val path = file.absolutePath
      return when {
        filesDeletedTooOld.contains(path) -> "older than all other hprof files"
        filesDeletedRemoveLeak.contains(path) -> "leak manually removed"
        else -> "unknown"
      }
    }
  }
}
