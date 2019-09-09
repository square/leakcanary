package leakcanary

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Ignore
import org.junit.Test
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import shark.HeapAnalyzer
import shark.ObjectInspectors.KEYED_WEAK_REFERENCE
import shark.OnAnalysisProgressListener
import shark.OnAnalysisProgressListener.Step
import shark.SharkLog
import java.io.File
import java.io.FileOutputStream

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
    Thread.sleep(20000)

    val analyzer = HeapAnalyzer(object : OnAnalysisProgressListener {
      override fun onAnalysisProgress(step: Step) {
        Log.d("LeakCanary", step.name)
      }
    })
    val result = analyzer.analyze(
        heapDumpFile = heapDumpFile,
        referenceMatchers = AndroidReferenceMatchers.appDefaults,
        objectInspectors = AndroidObjectInspectors.appDefaults,
        computeRetainedHeapSize = true,
        leakFinders = listOf(KEYED_WEAK_REFERENCE)
    )
    SharkLog.d { result.toString() }
    // Giving time to stop CPU profiler (otherwise trace won't succeed)
    Thread.sleep(20000)
  }

}

