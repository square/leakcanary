package leakcanary

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import leakcanary.AnalyzerProgressListener.Step
import leakcanary.internal.perflib.PerflibHeapAnalyzer
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.concurrent.Executor

/**
 * Instrumentation test that runs the two heap analyzer implementations on the same heap
 * dump and logs how they perform. This isn't meant to run as part of the test suite.
 */
@Ignore
class HeapAnalyzerComparisonTest {

  @Volatile
  var firstMaxMemoryUsed = 0L

  @Volatile
  var secondMaxMemoryUsed = 0L

  @Test fun compareHprofParsers() {
    leaking = Date()

    val clock = object : Clock {
      override fun uptimeMillis(): Long {
        return SystemClock.uptimeMillis()
      }
    }
    val executor = Executor { command -> command.run() }
    val onReferenceRetained = {}
    val refWatcher = RefWatcher(clock, executor, onReferenceRetained)
    refWatcher.watch(leaking)

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext

    val heapDumpFile = File(context.filesDir, "HeapAnalyzerComparisonTest.hprof")

    val heapDump = PerflibHeapDump.builder(heapDumpFile)
        .build()

    SystemClock.sleep(2000)

    val retainedKeys = refWatcher.retainedKeys
    HeapDumpMemoryStore.setRetainedKeysForHeapDump(retainedKeys)
    HeapDumpMemoryStore.heapDumpUptimeMillis = SystemClock.uptimeMillis()

    Debug.dumpHprofData(heapDumpFile.absolutePath)

    val mainHandler = Handler(Looper.getMainLooper())

    val runtime = Runtime.getRuntime()

    val logMemory: Runnable = object : Runnable {
      override fun run() {
        val memoryUsed = runtime.totalMemory() - runtime.freeMemory()
        CanaryLog.d("Memory: %d Mb", memoryUsed / 1048576L)
        mainHandler.postDelayed(this, 1000)
      }
    }
    logMemory.run()

    SystemClock.sleep(2000)
    GcTrigger.Default.runGc()
    SystemClock.sleep(2000)

    val memoryBeforeFirst = runtime.totalMemory() - runtime.freeMemory()

    val countMemory1: Runnable = object : Runnable {
      override fun run() {
        val memoryUsed = runtime.totalMemory() - runtime.freeMemory()
        firstMaxMemoryUsed = Math.max(firstMaxMemoryUsed, memoryUsed)
        mainHandler.postDelayed(this, 100)
      }
    }
    countMemory1.run()

    val listener = object : AnalyzerProgressListener {
      override fun onProgressUpdate(step: Step) {
        CanaryLog.d("Step %s", step)
      }

    }
    CanaryLog.d("Starting first analysis")
    val firstAnalysis = PerflibHeapAnalyzer(listener).checkForLeaks(
        heapDump, PerflibAndroidExcludedRefs.createAppDefaults().build()
    ) as HeapAnalysisSuccess
    CanaryLog.d("Done with first analysis")
    val memoryUsedFirstInMb = (firstMaxMemoryUsed - memoryBeforeFirst) / 1048576L

    SystemClock.sleep(2000)
    GcTrigger.Default.runGc()
    SystemClock.sleep(2000)
    val memoryBeforeSecond = runtime.totalMemory() - runtime.freeMemory()

    val countMemory2: Runnable = object : Runnable {
      override fun run() {
        val memoryUsed = runtime.totalMemory() - runtime.freeMemory()
        secondMaxMemoryUsed = Math.max(secondMaxMemoryUsed, memoryUsed)
        mainHandler.postDelayed(this, 100)
      }
    }
    countMemory2.run()

    val config = LeakCanary.config
    val secondAnalysis = HeapAnalyzer(listener)
        .checkForLeaks(
            heapDumpFile, config.exclusionsFactory, config.computeRetainedHeapSize,
            config.leakInspectors, config.labelers
        ) as HeapAnalysisSuccess
    val memoryUsedSecondInMb = (secondMaxMemoryUsed - memoryBeforeSecond) / 1048576L

    CanaryLog.d(
        "Perflib analysis used %d Mb took %d ms", memoryUsedFirstInMb,
        firstAnalysis.analysisDurationMillis
    )
    CanaryLog.d(
        "Random Access analysis used %d Mb took %d ms", memoryUsedSecondInMb,
        secondAnalysis.analysisDurationMillis
    )


    require(firstAnalysis.retainedInstances.size == 1)
    require(secondAnalysis.retainedInstances.size == 1)

    firstAnalysis.retainedInstances[0] as LeakingInstance
    secondAnalysis.retainedInstances[0] as LeakingInstance
  }

  companion object {
    private lateinit var leaking: Any
  }
}
