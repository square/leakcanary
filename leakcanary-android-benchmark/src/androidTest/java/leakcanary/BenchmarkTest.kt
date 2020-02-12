package leakcanary

import android.util.Log
import android.util.LruCache
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import shark.HeapAnalyzer
import shark.KeyedWeakReferenceFinder
import shark.OnAnalysisProgressListener
import shark.OnAnalysisProgressListener.Step
import shark.internal.hppc.LongObjectScatterMap
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class BenchmarkTest {

  @get:Rule
  val benchmarkRule = BenchmarkRule()

  @Test fun lruTest() {
    val cache = LruCache<Long, String>(3000)
    benchmarkRule.measureRepeated {
      for (i in 0..3000) {
        cache.put(i + 1500L, "Haha $i")
      }
    }
  }

  @Test fun scatterTest() {
    val cache = LongObjectScatterMap<String>()
    benchmarkRule.measureRepeated {
      for (i in 0..3000) {
        cache[i + 1500L] = "Haha $i"
      }
    }
  }

  // Kept for reference

  /*@Test fun analyzeLargeDump() {
      profileAnalysis("large-dump.hprof")
  }*/

  private fun profileAnalysis(fileName: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext

    val heapDumpFile = File(context.filesDir, "ProfiledTest.hprof")
    context.assets.open(fileName)
        .copyTo(FileOutputStream(heapDumpFile))

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

}

