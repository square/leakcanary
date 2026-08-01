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

import android.content.Context
import shark.SharkLog
import java.io.File
import java.io.FilenameFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Provides access to where heap dumps and analysis results will be stored.
 *
 * Heap dumps go to a `leakcanary` directory in [Context.getNoBackupFilesDir]. That directory
 * behaves the same way on every Android version: it needs no permission, it isn't visible to other
 * apps, and unlike [Context.getCacheDir] the system won't delete a heap dump that an analysis
 * hasn't read yet when the device runs low on storage. It's also excluded from Android Auto Backup,
 * which [Context.getFilesDir] isn't, so heap dumps never count against the user's backup quota.
 */
internal class LeakDirectoryProvider constructor(
  context: Context,
  private val maxStoredHeapDumps: () -> Int
) {
  private val context: Context = context.applicationContext

  fun newHeapDumpFile(): File? {
    cleanupOldHeapDumps()

    val heapDumpDirectory = heapDumpDirectory()
    if (!directoryWritableAfterMkdirs(heapDumpDirectory)) {
      SharkLog.d {
        "Could not create heap dump directory [${heapDumpDirectory.absolutePath}]"
      }
      return null
    }

    val fileName = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'.hprof'", Locale.US).format(Date())
    return File(heapDumpDirectory, fileName)
  }

  private fun heapDumpDirectory(): File = File(context.noBackupFilesDir, "leakcanary")

  private fun directoryWritableAfterMkdirs(directory: File): Boolean {
    val success = directory.mkdirs()
    return (success || directory.exists()) && directory.canWrite()
  }

  private fun cleanupOldHeapDumps() {
    val hprofFiles = listHeapDumpFiles { _, name ->
      name.endsWith(
        HPROF_SUFFIX
      )
    }
    val maxStoredHeapDumps = maxStoredHeapDumps()
    if (maxStoredHeapDumps < 1) {
      throw IllegalArgumentException("maxStoredHeapDumps must be at least 1")
    }

    val filesToRemove = hprofFiles.size - maxStoredHeapDumps
    if (filesToRemove > 0) {
      SharkLog.d { "Removing $filesToRemove heap dumps" }
      // Sort with oldest modified first.
      hprofFiles.sortWith { lhs, rhs ->
        java.lang.Long.valueOf(lhs.lastModified())
          .compareTo(rhs.lastModified())
      }
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

  private fun listHeapDumpFiles(filter: FilenameFilter): MutableList<File> {
    val files = heapDumpDirectory().listFiles(filter) ?: emptyArray()
    return files.toMutableList()
  }

  companion object {
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
