package leakcanary

import android.os.Trace
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Test
import shark.AndroidMetadataExtractor
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import shark.HeapAnalyzer
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.KeyedWeakReferenceFinder
import shark.SharkLog

class Api23Test {

  /*

  ./record_android_trace -o trace_file.perfetto-trace -b 500mb -a com.squareup.leakcanary.instrumentation.test sched freq idle am wm gfx view binder_driver hal dalvik camera input res memory

   */
  @Test fun analyze_hprof() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext

    val heapDumpFile = File(context.filesDir, "api_23_dump.hprof")
    context.assets.open("api_23_dump.hprof")
      .copyTo(FileOutputStream(heapDumpFile))

    Trace.beginSection("Analysis")
    Trace.beginSection("Open graph ")
    heapDumpFile.openHeapGraph()
      .use { graph ->
        val analyzer = HeapAnalyzer {
          Trace.endSection()
          Trace.beginSection(it.humanReadableName)
          SharkLog.d { it.humanReadableName }
        }

        val result = analyzer.analyze(
          heapDumpFile = heapDumpFile,
          graph = graph,
          leakingObjectFinder = KeyedWeakReferenceFinder,
          referenceMatchers = AndroidReferenceMatchers.appDefaults,
          computeRetainedHeapSize = true,
          objectInspectors = AndroidObjectInspectors.appDefaults,
          metadataExtractor = AndroidMetadataExtractor
        )
        Trace.endSection()
        SharkLog.d { result.toString() }
      }
    Trace.endSection()
  }
}
