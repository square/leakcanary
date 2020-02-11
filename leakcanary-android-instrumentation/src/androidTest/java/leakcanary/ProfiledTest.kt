package leakcanary

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Ignore
import org.junit.Test
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import shark.HeapAnalyzer
import shark.KeyedWeakReferenceFinder
import shark.OnAnalysisProgressListener
import shark.OnAnalysisProgressListener.Step
import shark.SharkLog
import java.io.File
import java.io.FileOutputStream
import kotlin.system.measureTimeMillis

@Suppress("ConstantConditionIf")
class ProfiledTest {

  @Ignore
  @Test fun analyzeLargeDump() {
    profileAnalysis("large-dump.hprof")
  }

  private fun profileAnalysis(fileName: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext

    val heapDumpFile = File(context.filesDir, "ProfiledTest.hprof")
    context.assets.open(fileName)
        .copyTo(FileOutputStream(heapDumpFile))

    SharkLog.d { "Waiting, please start profiler" }
    if (ATTACH_PROFILER) Profiler.waitForSamplingStart()

    val times = if (ATTACH_PROFILER) 1 else 10
    repeat(times) {
      val time = measureTimeMillis {
        val analyzer = HeapAnalyzer(object : OnAnalysisProgressListener {
          override fun onAnalysisProgress(step: Step) {
            Log.d("LeakCanary", step.name)
          }
        })
        analyzer.analyze(
            heapDumpFile = heapDumpFile,
            leakingObjectFinder = KeyedWeakReferenceFinder,
            referenceMatchers = AndroidReferenceMatchers.appDefaults,
            objectInspectors = AndroidObjectInspectors.appDefaults,
            computeRetainedHeapSize = true
        )
      }
      SharkLog.d { "Measured profileAnalysis time = $time" }
    }

    // Giving time to stop CPU profiler (otherwise trace won't succeed)
    if (ATTACH_PROFILER) Profiler.waitForSamplingStop()
  }

  companion object {
    const val ATTACH_PROFILER = false
  }
}

