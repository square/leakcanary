package leakcanary.internal

import android.os.Debug
import android.os.SystemClock
import leakcanary.HeapAnalysisConfig
import leakcanary.HeapAnalysisInterceptor
import leakcanary.HeapAnalysisJob
import leakcanary.HeapAnalysisJob.Result
import leakcanary.HeapAnalysisJob.Result.Canceled
import leakcanary.HeapAnalysisJob.Result.Done
import leakcanary.JobContext
import okio.buffer
import okio.sink
import shark.CloseableHeapGraph
import shark.ConstantMemoryMetricsDualSourceProvider
import shark.DualSourceProvider
import shark.HeapAnalysis
import shark.HeapAnalysisException
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HprofHeapGraph
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofPrimitiveArrayStripper
import shark.OnAnalysisProgressListener
import shark.RandomAccessSource
import shark.SharkLog
import shark.StreamingSourceProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class RealHeapAnalysisJob(
  private val heapDumpDirectoryProvider: () -> File,
  private val config: HeapAnalysisConfig,
  private val interceptors: List<HeapAnalysisInterceptor>,
  override val context: JobContext
) : HeapAnalysisJob, HeapAnalysisInterceptor.Chain {

  private val heapDumpDirectory by lazy {
    heapDumpDirectoryProvider()
  }

  private val _canceled = AtomicReference<Canceled?>()

  private val _executed = AtomicBoolean(false)

  private lateinit var executionThread: Thread

  private var interceptorIndex = 0

  private var analysisStep: OnAnalysisProgressListener.Step? = null

  override val executed
    get() = _executed.get()

  override val canceled
    get() = _canceled.get() != null

  override val job: HeapAnalysisJob
    get() = this

  override fun execute(): Result {
    check(_executed.compareAndSet(false, true)) { "HeapAnalysisJob can only be executed once" }
    SharkLog.d { "Starting heap analysis job" }
    executionThread = Thread.currentThread()
    return proceed()
  }

  override fun cancel(cancelReason: String) {
    // If cancel is called several times, we use the first cancel reason.
    _canceled.compareAndSet(null, Canceled(cancelReason))
  }

  override fun proceed(): Result {
    check(Thread.currentThread() == executionThread) {
      "Interceptor.Chain.proceed() called from unexpected thread ${Thread.currentThread()} instead of $executionThread"
    }
    check(interceptorIndex <= interceptors.size) {
      "Interceptor.Chain.proceed() should be called max once per interceptor"
    }
    _canceled.get()?.let {
      interceptorIndex = interceptors.size + 1
      return it
    }
    if (interceptorIndex < interceptors.size) {
      val currentInterceptor = interceptors[interceptorIndex]
      interceptorIndex++
      return currentInterceptor.intercept(this)
    } else {
      interceptorIndex++
      val result = dumpAndAnalyzeHeap()
      val analysis = result.analysis
      analysis.heapDumpFile.delete()
      if (analysis is HeapAnalysisFailure) {
        val cause = analysis.exception.cause
        if (cause is StopAnalysis) {
          return _canceled.get()!!.run {
            copy(cancelReason = "$cancelReason (stopped at ${cause.step})")
          }
        }
      }
      return result
    }
  }

  private fun dumpAndAnalyzeHeap(): Done {
    val filesDir = heapDumpDirectory
    filesDir.mkdirs()
    val fileNameBase = "$HPROF_PREFIX${UUID.randomUUID()}"
    val sensitiveHeapDumpFile = File(filesDir, "$fileNameBase$HPROF_SUFFIX").apply {
      // Any call to System.exit(0) will run shutdown hooks that will attempt to remove this
      // file. Note that this is best effort, and won't delete if the VM is killed by the system.
      deleteOnExit()
    }

    val heapDumpStart = SystemClock.uptimeMillis()
    saveHeapDumpTime(heapDumpStart)

    var dumpDurationMillis = -1L
    var analysisDurationMillis = -1L
    var heapDumpFile = sensitiveHeapDumpFile

    try {
      runGc()
      dumpHeap(sensitiveHeapDumpFile)
      dumpDurationMillis = SystemClock.uptimeMillis() - heapDumpStart

      val stripDurationMillis =
        if (config.stripHeapDump) {
          leakcanary.internal.friendly.measureDurationMillis {
            val strippedHeapDumpFile = File(filesDir, "$fileNameBase-stripped$HPROF_SUFFIX").apply {
              deleteOnExit()
            }
            heapDumpFile = strippedHeapDumpFile
            try {
              stripHeapDump(sensitiveHeapDumpFile, strippedHeapDumpFile)
            } finally {
              sensitiveHeapDumpFile.delete()
            }
          }
        } else null

      return analyzeHeapWithStats(heapDumpFile).let { (heapAnalysis, stats) ->
        when (heapAnalysis) {
          is HeapAnalysisSuccess -> {
            val metadata = heapAnalysis.metadata.toMutableMap()
            metadata["Stats"] = stats
            if (config.stripHeapDump) {
              metadata["Hprof stripping duration"] = "$stripDurationMillis ms"
            }
            Done(
              heapAnalysis.copy(
                dumpDurationMillis = dumpDurationMillis,
                metadata = metadata
              ), stripDurationMillis
            )
          }
          is HeapAnalysisFailure -> Done(
            heapAnalysis.copy(
              dumpDurationMillis = dumpDurationMillis,
              analysisDurationMillis = (SystemClock.uptimeMillis() - heapDumpStart) - dumpDurationMillis
            ), stripDurationMillis
          )
        }
      }
    } catch (throwable: Throwable) {
      if (dumpDurationMillis == -1L) {
        dumpDurationMillis = SystemClock.uptimeMillis() - heapDumpStart
      }
      if (analysisDurationMillis == -1L) {
        analysisDurationMillis = (SystemClock.uptimeMillis() - heapDumpStart) - dumpDurationMillis
      }
      return Done(
        HeapAnalysisFailure(
          heapDumpFile = heapDumpFile,
          createdAtTimeMillis = System.currentTimeMillis(),
          dumpDurationMillis = dumpDurationMillis,
          analysisDurationMillis = analysisDurationMillis,
          exception = HeapAnalysisException(throwable)
        )
      )
    }
  }

  private fun runGc() {
    // Code taken from AOSP FinalizationTest:
    // https://android.googlesource.com/platform/libcore/+/master/support/src/test/java/libcore/
    // java/lang/ref/FinalizationTester.java
    // System.gc() does not garbage collect every time. Runtime.gc() is
    // more likely to perform a gc.
    Runtime.getRuntime()
      .gc()
    enqueueReferences()
    System.runFinalization()
  }

  private fun enqueueReferences() {
    // Hack. We don't have a programmatic way to wait for the reference queue daemon to move
    // references to the appropriate queues.
    try {
      Thread.sleep(100)
    } catch (e: InterruptedException) {
      throw AssertionError()
    }
  }

  private fun saveHeapDumpTime(heapDumpUptimeMillis: Long) {
    try {
      Class.forName("leakcanary.KeyedWeakReference")
        .getDeclaredField("heapDumpUptimeMillis")
        .apply { isAccessible = true }
        .set(null, heapDumpUptimeMillis)
    } catch (ignored: Throwable) {
      SharkLog.d(ignored) { "KeyedWeakReference.heapDumpUptimeMillis not updated" }
    }
  }

  private fun dumpHeap(heapDumpFile: File) {
    Debug.dumpHprofData(heapDumpFile.absolutePath)

    check(heapDumpFile.exists()) {
      "File does not exist after dump"
    }

    check(heapDumpFile.length() > 0L) {
      "File has length ${heapDumpFile.length()} after dump"
    }
  }

  private fun stripHeapDump(
    sourceHeapDumpFile: File,
    strippedHeapDumpFile: File
  ) {
    val sensitiveSourceProvider =
      StoppableFileSourceProvider(sourceHeapDumpFile) {
        checkStopAnalysis("stripping heap dump")
      }

    var openCalls = 0
    val deletingFileSourceProvider = StreamingSourceProvider {
      openCalls++
      sensitiveSourceProvider.openStreamingSource().apply {
        if (openCalls == 2) {
          // Using the Unix trick of deleting the file as soon as all readers have opened it.
          // No new readers/writers will be able to access the file, but all existing
          // ones will still have access until the last one closes the file.
          SharkLog.d { "Deleting $sourceHeapDumpFile eagerly" }
          sourceHeapDumpFile.delete()
        }
      }
    }

    val strippedHprofSink = strippedHeapDumpFile.outputStream().sink().buffer()
    val stripper = HprofPrimitiveArrayStripper()

    stripper.stripPrimitiveArrays(deletingFileSourceProvider, strippedHprofSink)
  }

  private fun analyzeHeapWithStats(heapDumpFile: File): Pair<HeapAnalysis, String> {
    val fileLength = heapDumpFile.length()
    val analysisSourceProvider = ConstantMemoryMetricsDualSourceProvider(
      StoppableFileSourceProvider(heapDumpFile) {
        checkStopAnalysis(analysisStep?.name ?: "Reading heap dump")
      })

    val deletingFileSourceProvider = object : DualSourceProvider {
      override fun openStreamingSource() = analysisSourceProvider.openStreamingSource()

      override fun openRandomAccessSource(): RandomAccessSource {
        SharkLog.d { "Deleting $heapDumpFile eagerly" }
        return analysisSourceProvider.openRandomAccessSource().apply {
          // Using the Unix trick of deleting the file as soon as all readers have opened it.
          // No new readers/writers will be able to access the file, but all existing
          // ones will still have access until the last one closes the file.
          heapDumpFile.delete()
        }
      }
    }

    return deletingFileSourceProvider.openHeapGraph().use { graph ->
      val heapAnalysis = analyzeHeap(heapDumpFile, graph)
      val lruCacheStats = (graph as HprofHeapGraph).lruCacheStats()
      val randomAccessStats =
        "RandomAccess[" +
          "bytes=${analysisSourceProvider.randomAccessByteReads}," +
          "reads=${analysisSourceProvider.randomAccessReadCount}," +
          "travel=${analysisSourceProvider.randomAccessByteTravel}," +
          "range=${analysisSourceProvider.byteTravelRange}," +
          "size=$fileLength" +
          "]"
      val stats = "$lruCacheStats $randomAccessStats"
      (heapAnalysis to stats)
    }
  }

  private fun analyzeHeap(
    analyzedHeapDumpFile: File,
    graph: CloseableHeapGraph
  ): HeapAnalysis {
    val stepListener = OnAnalysisProgressListener { step ->
      analysisStep = step
      checkStopAnalysis(step.name)
      SharkLog.d { "Analysis in progress, working on: ${step.name}" }
    }

    val heapAnalyzer = HeapAnalyzer(stepListener)
    return heapAnalyzer.analyze(
      heapDumpFile = analyzedHeapDumpFile,
      graph = graph,
      leakingObjectFinder = config.leakingObjectFinder,
      referenceMatchers = config.referenceMatchers,
      computeRetainedHeapSize = config.computeRetainedHeapSize,
      objectInspectors = config.objectInspectors,
      metadataExtractor = config.metadataExtractor
    )
  }

  private fun checkStopAnalysis(step: String) {
    if (_canceled.get() != null) {
      throw StopAnalysis(step)
    }
  }

  class StopAnalysis(val step: String) : Exception() {
    override fun fillInStackTrace(): Throwable {
      // Skip filling in stacktrace.
      return this
    }
  }

  companion object {
    const val HPROF_PREFIX = "heap-"
    const val HPROF_SUFFIX = ".hprof"
  }
}