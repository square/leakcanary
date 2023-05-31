package leakcanary

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import leakcanary.internal.friendly.measureDurationMillis
import org.junit.Test
import shark.FileSourceProvider
import shark.HprofHeader
import shark.HprofIndex
import shark.SharkLog

class IndexingTest {

  @Test fun indexHprof() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext

    val heapDumpFile = File(context.filesDir, "AnalysisDurationTest.hprof")
    context.assets.open("large-dump.hprof").copyTo(FileOutputStream(heapDumpFile))

    val sourceProvider = FileSourceProvider(heapDumpFile)
    val parsedHeaders = HprofHeader.parseHeaderOf(heapDumpFile)

    SharkLog.d { "Start indexing" }
    val durationMs = measureDurationMillis {
      HprofIndex.indexRecordsOf(
        hprofSourceProvider = sourceProvider,
        hprofHeader = parsedHeaders
      )
    }
    SharkLog.d { "Indexing took $durationMs ms" }
  }
}

