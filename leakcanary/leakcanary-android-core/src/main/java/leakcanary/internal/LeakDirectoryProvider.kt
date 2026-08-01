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
import android.database.sqlite.SQLiteDatabase
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.HeapDumpDeletionTable
import leakcanary.internal.activity.db.ScopedLeaksDb
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

  /**
   * Deletes heap dumps until at most [maxStoredHeapDumps] are left, oldest first, taking the ones
   * that were already analyzed before the ones that weren't.
   *
   * A heap dump no stored analysis was run on is still waiting for its analysis, which is queued on
   * WorkManager and therefore survives process death and reboots: it can run minutes or days after
   * the heap dump was created. Deleting that file leaves the analysis nothing to read, so it's the
   * last thing to go. The limit is still a limit though, so when every stored heap dump is waiting
   * the oldest one is deleted anyway, and why gets recorded so that the analysis failure can say so
   * instead of reporting a file that vanished for no reason.
   */
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
    if (filesToRemove <= 0) {
      return
    }
    SharkLog.d { "Removing $filesToRemove heap dumps" }
    ScopedLeaksDb.writableDatabase(context) { db ->
      val analyzedFilePaths = HeapAnalysisTable.retrieveAnalyzedHeapDumpFilePaths(db)
      val (analyzed, waitingForAnalysis) = hprofFiles.partition {
        it.absolutePath in analyzedFilePaths
      }
      val analyzedToRemove = minOf(filesToRemove, analyzed.size)
      deleteOldest(db, analyzed, analyzedToRemove, alreadyAnalyzedReason(maxStoredHeapDumps))
      val waitingToRemove = filesToRemove - analyzedToRemove
      if (waitingToRemove > 0) {
        SharkLog.d {
          "Removing $waitingToRemove heap dumps that are still waiting to be analyzed"
        }
        deleteOldest(
          db, waitingForAnalysis, waitingToRemove, stillWaitingReason(maxStoredHeapDumps)
        )
      }
    }
  }

  private fun deleteOldest(
    db: SQLiteDatabase,
    hprofFiles: List<File>,
    count: Int,
    reason: String
  ) {
    hprofFiles.sortedBy { it.lastModified() }
      .take(count)
      .forEach { file ->
        if (file.delete()) {
          HeapDumpDeletionTable.insert(db, file, reason)
        } else {
          SharkLog.d { "Could not delete old hprof file ${file.path}" }
        }
      }
  }

  private fun listHeapDumpFiles(filter: FilenameFilter): List<File> {
    return heapDumpDirectory().listFiles(filter)?.toList() ?: emptyList()
  }

  companion object {
    private const val HPROF_SUFFIX = ".hprof"

    private fun alreadyAnalyzedReason(maxStoredHeapDumps: Int) =
      "LeakCanary hit its maxStoredHeapDumps limit of $maxStoredHeapDumps and deleted this heap " +
        "dump, the oldest one it had already analyzed."

    private fun stillWaitingReason(maxStoredHeapDumps: Int) =
      "LeakCanary hit its maxStoredHeapDumps limit of $maxStoredHeapDumps with every stored heap " +
        "dump still waiting to be analyzed, so it deleted the oldest one. Raise " +
        "LeakCanary.Config.maxStoredHeapDumps if heap dumps pile up faster than they can be " +
        "analyzed."
  }
}
