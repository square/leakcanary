package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.LegacyHprofTest.WRAPS_ACTIVITY.DESTROYED
import shark.LegacyHprofTest.WRAPS_ACTIVITY.NOT_ACTIVITY
import shark.LegacyHprofTest.WRAPS_ACTIVITY.NOT_DESTROYED
import java.io.File

class LegacyHprofTest {

  @Test fun preM() {
    val analysis = analyzeHprof("leak_asynctask_pre_m.hprof")
    assertThat(analysis.applicationLeaks).hasSize(2)
    val leak1 = analysis.applicationLeaks[0]
    val leak2 = analysis.applicationLeaks[1]
    assertThat(leak1.className).isEqualTo("android.graphics.Bitmap")
    assertThat(leak2.className).isEqualTo("com.example.leakcanary.MainActivity")
    assertThat(analysis.metadata).isEqualTo(
        mapOf(
            "App process name" to "com.example.leakcanary",
            "Build.MANUFACTURER" to "Genymotion",
            "Build.VERSION.SDK_INT" to "19",
            "LeakCanary version" to "Unknown"
        )
    )
  }

  @Test fun androidM() {
    val analysis = analyzeHprof("leak_asynctask_m.hprof")

    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0]
    assertThat(leak.className).isEqualTo("com.example.leakcanary.MainActivity")
    assertThat(leak.leakTrace.elements[0].labels).contains("GC Root: System class")
  }

  @Test fun gcRootReferencesUnknownObject() {
    val analysis = analyzeHprof("gcroot_unknown_object.hprof")

    assertThat(analysis.applicationLeaks).hasSize(2)
  }

  @Test fun androidMStripped() {
    val stripper = HprofPrimitiveArrayStripper()
    val sourceHprof = fileFromResources("leak_asynctask_m.hprof")
    val strippedHprof = stripper.stripPrimitiveArrays(sourceHprof)

    assertThat(readThreadNames(sourceHprof)).contains("AsyncTask #1")
    assertThat(readThreadNames(strippedHprof)).allMatch { threadName ->
      threadName.all { character -> character == '?' }
    }
  }

  private fun readThreadNames(hprofFile: File): List<String> {
    val threadNames = Hprof.open(hprofFile)
        .use { hprof ->
          val graph = HprofHeapGraph.indexHprof(hprof)
          graph.findClassByName("java.lang.Thread")!!.instances.map { instance ->
            instance["java.lang.Thread", "name"]!!.value.readAsJavaString()!!
          }
              .toList()
        }
    return threadNames
  }

  @Test fun androidO() {
    val analysis = analyzeHprof("leak_asynctask_o.hprof")

    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0]
    assertThat(leak.className).isEqualTo("com.example.leakcanary.MainActivity")
  }

  private enum class WRAPS_ACTIVITY {
    DESTROYED,
    NOT_DESTROYED,
    NOT_ACTIVITY
  }

  @Test fun androidOCountActivityWrappingContexts() {
    val contextWrapperStatuses = Hprof.open(fileFromResources("leak_asynctask_o.hprof"))
        .use { hprof ->
          val graph = HprofHeapGraph.indexHprof(hprof)
          graph.instances.filter { it instanceOf "android.content.ContextWrapper" && !(it instanceOf "android.app.Activity") }
              .map { instance ->
                val reporter = ObjectReporter(instance)
                AndroidObjectInspectors.CONTEXT_WRAPPER.inspect(reporter)
                if (reporter.leakingReasons.size == 1) {
                  DESTROYED
                } else if (reporter.labels.size == 1) {
                  if ("Activity.mDestroyed false" in reporter.labels.first()) {
                    NOT_DESTROYED
                  } else {
                    NOT_ACTIVITY
                  }
                } else throw IllegalStateException(
                    "Unexpected, should have 1 leaking status ${reporter.leakingReasons} or one label ${reporter.labels}"
                )
              }
              .toList()
        }
    assertThat(contextWrapperStatuses.filter { it == DESTROYED }).hasSize(12)
    assertThat(contextWrapperStatuses.filter { it == NOT_DESTROYED }).hasSize(6)
    assertThat(contextWrapperStatuses.filter { it == NOT_ACTIVITY }).hasSize(1)
  }

  @Test fun gcRootInNonPrimaryHeap() {
    val analysis = analyzeHprof("gc_root_in_non_primary_heap.hprof")

    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0]
    assertThat(leak.className).isEqualTo("com.example.leakcanary.MainActivity")
  }

  private fun analyzeHprof(fileName: String): HeapAnalysisSuccess {
    return analyzeHprof(fileFromResources(fileName))
  }

  private fun fileFromResources(fileName: String): File {
    val classLoader = Thread.currentThread()
        .contextClassLoader
    val url = classLoader.getResource(fileName)
    return File(url.path)
  }

  private fun analyzeHprof(hprofFile: File): HeapAnalysisSuccess {
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = heapAnalyzer.analyze(
        heapDumpFile = hprofFile,
        referenceMatchers = AndroidReferenceMatchers.appDefaults,
        computeRetainedHeapSize = false,
        objectInspectors = AndroidObjectInspectors.appDefaults,
        metadataExtractor = AndroidMetadataExtractor
    )
    println(analysis)
    return analysis as HeapAnalysisSuccess
  }
}