package leakcanary

import android.app.Application
import leakcanary.internal.RealHeapAnalysisJob
import leakcanary.internal.RealHeapAnalysisJob.Companion.HPROF_PREFIX
import leakcanary.internal.RealHeapAnalysisJob.Companion.HPROF_SUFFIX
import java.io.File

class HeapAnalysisClient(
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
