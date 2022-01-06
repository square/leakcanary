package leakcanary

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import leakcanary.Profiler.runWithProfilerSampling
import org.junit.Ignore
import org.junit.Test
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import shark.HeapAnalyzer
import shark.KeyedWeakReferenceFinder
import shark.SharkLog

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
      .copyTo(heapDumpFile.outputStream())

    runWithProfilerSampling {
      val analyzer = HeapAnalyzer { step -> Log.d("LeakCanary", step.name) }
      val result = analyzer.analyze(
        heapDumpFile = heapDumpFile,
        leakingObjectFinder = KeyedWeakReferenceFinder,
        referenceMatchers = AndroidReferenceMatchers.appDefaults,
        objectInspectors = AndroidObjectInspectors.appDefaults,
        computeRetainedHeapSize = true
      )
      SharkLog.d { result.toString() }
    }
  }
}

