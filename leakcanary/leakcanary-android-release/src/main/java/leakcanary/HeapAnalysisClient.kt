package leakcanary

import android.app.Application
import leakcanary.internal.RealHeapAnalysisJob
import leakcanary.internal.RealHeapAnalysisJob.Companion.HPROF_PREFIX
import leakcanary.internal.RealHeapAnalysisJob.Companion.HPROF_SUFFIX
import java.io.File

class HeapAnalysisClient(
  /**
   * Provides the directory where heap dumps should be stored.
   * The passed in directory SHOULD be part of the app internal storage, and private to the
   * app, to guarantee no other app can read the heap dumps.
   *
   * You should probably pass in [android.app.Application.getCacheDir] or a sub directory
   * of the cache directory, as the cache directory will never be backed up and it will be
   * cleared if the user needs space.
   *
   * This will be invoked on the thread used to execute analysis jobs. The main reason for
   * the delayed invocation is because [android.app.Application.getCacheDir] might perform
   * IO and trigger StrictMode violations.
   */
  private val heapDumpDirectoryProvider: () -> File,
  private val config: HeapAnalysisConfig,
  private val interceptors: List<HeapAnalysisInterceptor>
) {

  fun newJob(context: JobContext = JobContext()): HeapAnalysisJob {
    return RealHeapAnalysisJob(heapDumpDirectoryProvider, config, interceptors, context)
  }

  fun deleteHeapDumpFiles() {
    val heapDumpFiles = heapDumpDirectoryProvider().listFiles { _, name ->
      name.startsWith(HPROF_PREFIX) && name.endsWith(HPROF_SUFFIX)
    }
    heapDumpFiles?.forEach { it.delete() }
  }

  companion object {
    fun defaultInterceptors(application: Application): List<HeapAnalysisInterceptor> {
      return listOf(
        GoodAndroidVersionInterceptor(),
        MinimumDiskSpaceInterceptor(application),
        MinimumMemoryInterceptor(application),
        MinimumElapsedSinceStartInterceptor(),
        OncePerPeriodInterceptor(application),
        SaveResourceIdsInterceptor(application.resources)
      )
    }
  }
}
