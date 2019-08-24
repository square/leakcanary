package leakcanary

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import shark.Hprof
import shark.HprofHeapGraph
import shark.SharkLog
import java.io.File
import java.io.FileOutputStream

class IndexingTest {

  @Test fun indexHprof() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext

    val heapDumpFile = File(context.filesDir, "AnalysisDurationTest.hprof")
    context.assets.open("large-dump.hprof").copyTo(FileOutputStream(heapDumpFile))

    Hprof.open(heapDumpFile).use { hprof ->
      SharkLog.d { "Start indexing" }
      val before = SystemClock.uptimeMillis()
      HprofHeapGraph.indexHprof(hprof)
      val durationMs = (SystemClock.uptimeMillis() - before)
      SharkLog.d { "Indexing took $durationMs ms" }
    }
  }
}

