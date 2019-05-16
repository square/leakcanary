package leakcanary

import android.os.Debug
import android.os.SystemClock
import androidx.benchmark.BenchmarkRule
import androidx.benchmark.measureRepeated
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.concurrent.Executor

@Ignore
class AnalysisBenchmarkTest {

  @get:Rule
  val benchmarkRule = BenchmarkRule()

  @Test fun benchmarkAnalysis() {
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

    val retainedKeys = refWatcher.retainedKeys
    HeapDumpMemoryStore.setRetainedKeysForHeapDump(retainedKeys)
    HeapDumpMemoryStore.heapDumpUptimeMillis = SystemClock.uptimeMillis()

    Debug.dumpHprofData(heapDumpFile.absolutePath)

    val config = LeakCanary.config
    val heapAnalyzer = HeapAnalyzer(AnalyzerProgressListener.NONE)


    benchmarkRule.measureRepeated {
      val analysis = heapAnalyzer.checkForLeaks(
          heapDumpFile, config.exclusionsFactory, true, config.leakInspectors, config.labelers
      ) as HeapAnalysisSuccess
      require(analysis.retainedInstances.size == 1)
      require(analysis.retainedInstances[0] is LeakingInstance)
    }
  }

  companion object {
    private lateinit var leaking: Any
  }
}
