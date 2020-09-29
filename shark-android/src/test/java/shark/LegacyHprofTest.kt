package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.LeakTrace.GcRootType
import shark.LegacyHprofTest.WRAPS_ACTIVITY.DESTROYED
import shark.LegacyHprofTest.WRAPS_ACTIVITY.NOT_ACTIVITY
import shark.LegacyHprofTest.WRAPS_ACTIVITY.NOT_DESTROYED
import shark.SharkLog.Logger
import java.io.File

class LegacyHprofTest {

  @Test fun preM() {
    val analysis = analyzeHprof("leak_asynctask_pre_m.hprof")
    assertThat(analysis.applicationLeaks).hasSize(2)
    val leak1 = analysis.applicationLeaks[0].leakTraces.first()
    val leak2 = analysis.applicationLeaks[1].leakTraces.first()
    assertThat(leak1.leakingObject.className).isEqualTo("android.graphics.Bitmap")
    assertThat(leak2.leakingObject.className).isEqualTo("com.example.leakcanary.MainActivity")
    assertThat(analysis.metadata).containsAllEntriesOf(
        mapOf(
            "App process name" to "com.example.leakcanary",
            "Build.MANUFACTURER" to "Genymotion",
            "Build.VERSION.SDK_INT" to "19",
            "LeakCanary version" to "Unknown"
        )
    )
    assertThat(analysis.allLeaks.sumBy { it.totalRetainedHeapByteSize!! }).isEqualTo(193431)
  }

  @Test fun androidM() {
    val analysis = analyzeHprof("leak_asynctask_m.hprof")

    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leak.leakingObject.className).isEqualTo("com.example.leakcanary.MainActivity")
    assertThat(leak.gcRootType).isEqualTo(GcRootType.STICKY_CLASS)
    assertThat(analysis.allLeaks.sumBy { it.totalRetainedHeapByteSize!! }).isEqualTo(49584)
  }

  @Test fun gcRootReferencesUnknownObject() {
    val analysis = analyzeHprof("gcroot_unknown_object.hprof")

    assertThat(analysis.applicationLeaks).hasSize(2)
    assertThat(analysis.allLeaks.sumBy { it.totalRetainedHeapByteSize!! }).isEqualTo(5306218)
  }

  @Test fun androidMStripped() {
    val stripper = HprofPrimitiveArrayStripper()
    val sourceHprof = "leak_asynctask_m.hprof".classpathFile()
    val strippedHprof = stripper.stripPrimitiveArrays(sourceHprof)

    assertThat(readThreadNames(sourceHprof)).contains("AsyncTask #1")
    assertThat(readThreadNames(strippedHprof)).allMatch { threadName ->
      threadName.all { character -> character == '?' }
    }
  }

  private fun readThreadNames(hprofFile: File): List<String> {
    return hprofFile.openHeapGraph().use { graph ->
      graph.findClassByName("java.lang.Thread")!!.instances.map { instance ->
        instance["java.lang.Thread", "name"]!!.value.readAsJavaString()!!
      }
          .toList()
    }
  }

  @Test fun androidO() {
    val analysis = analyzeHprof("leak_asynctask_o.hprof")

    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leak.leakingObject.className).isEqualTo("com.example.leakcanary.MainActivity")
    assertThat(analysis.allLeaks.sumBy { it.totalRetainedHeapByteSize!! }).isEqualTo(211038)
  }

  private enum class WRAPS_ACTIVITY {
    DESTROYED,
    NOT_DESTROYED,
    NOT_ACTIVITY
  }

  @Test fun `AndroidObjectInspectors#CONTEXT_FIELD labels Context fields`() {
    val toastLabels = "leak_asynctask_o.hprof".classpathFile().openHeapGraph().use { graph ->
      graph.instances.filter { it.instanceClassName == "android.widget.Toast" }
          .map { instance ->
            ObjectReporter(instance).apply {
              AndroidObjectInspectors.CONTEXT_FIELD.inspect(this)
            }.labels.joinToString(",")
          }.toList()
    }
    assertThat(toastLabels).containsExactly("mContext instance of com.example.leakcanary.ExampleApplication")
  }

  @Test fun androidOCountActivityWrappingContexts() {
    val contextWrapperStatuses = Hprof.open("leak_asynctask_o.hprof".classpathFile())
        .use { hprof ->
          val graph = HprofHeapGraph.indexHprof(hprof)
          graph.instances.filter {
            it instanceOf "android.content.ContextWrapper"
                && !(it instanceOf "android.app.Activity")
                && !(it instanceOf "android.app.Application")
                && !(it instanceOf "android.app.Service")
          }
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
    assertThat(contextWrapperStatuses.filter { it == NOT_ACTIVITY }).hasSize(0)
  }

  @Test fun gcRootInNonPrimaryHeap() {
    val analysis = analyzeHprof("gc_root_in_non_primary_heap.hprof")

    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0].leakTraces.first()
    assertThat(leak.leakingObject.className).isEqualTo("com.example.leakcanary.MainActivity")
  }

  private fun analyzeHprof(fileName: String): HeapAnalysisSuccess {
    return analyzeHprof(fileName.classpathFile())
  }

  private fun analyzeHprof(hprofFile: File): HeapAnalysisSuccess {
    SharkLog.logger = object : Logger {
      override fun d(message: String) {
        println(message)
      }

      override fun d(
        throwable: Throwable,
        message: String
      ) {
        println(message)
        throwable.printStackTrace()
      }

    }
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = heapAnalyzer.analyze(
        heapDumpFile = hprofFile,
        leakingObjectFinder = FilteringLeakingObjectFinder(
            AndroidObjectInspectors.appLeakingObjectFilters
        ),
        referenceMatchers = AndroidReferenceMatchers.appDefaults,
        computeRetainedHeapSize = true,
        objectInspectors = AndroidObjectInspectors.appDefaults,
        metadataExtractor = AndroidMetadataExtractor
    )
    println(analysis)
    return analysis as HeapAnalysisSuccess
  }
}