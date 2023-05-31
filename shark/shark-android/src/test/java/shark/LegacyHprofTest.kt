package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HeapObject.HeapInstance
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecordTag.LOAD_CLASS
import shark.HprofRecordTag.ROOT_STICKY_CLASS
import shark.HprofRecordTag.STRING_IN_UTF8
import shark.LeakTrace.GcRootType
import shark.LegacyHprofTest.WRAPS_ACTIVITY.DESTROYED
import shark.LegacyHprofTest.WRAPS_ACTIVITY.NOT_ACTIVITY
import shark.LegacyHprofTest.WRAPS_ACTIVITY.NOT_DESTROYED
import shark.SharkLog.Logger

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
    assertThat(toastLabels).containsExactly(
      "mContext instance of com.example.leakcanary.ExampleApplication"
    )
  }

  @Test fun androidOCountActivityWrappingContexts() {
    val contextWrapperStatuses = "leak_asynctask_o.hprof".classpathFile()
      .openHeapGraph().use { graph ->
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

  @Test fun `MessageQueue shows list of messages as array`() {
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = heapAnalyzer.analyze(
      heapDumpFile = "gc_root_in_non_primary_heap.hprof".classpathFile(),
      leakingObjectFinder = FilteringLeakingObjectFinder(
        listOf(FilteringLeakingObjectFinder.LeakingObjectFilter { heapObject ->
          heapObject is HeapInstance &&
            heapObject instanceOf "android.os.Message" &&
            heapObject["android.os.Message", "target"]?.valueAsInstance?.instanceClassName == "android.app.ActivityThread\$H" &&
            heapObject["android.os.Message", "what"]!!.value.asInt!! == 132 // ENABLE_JIT
        })
      ),
      referenceMatchers = AndroidReferenceMatchers.appDefaults,
      computeRetainedHeapSize = true,
      objectInspectors = AndroidObjectInspectors.appDefaults,
      metadataExtractor = AndroidMetadataExtractor
    )
    println(analysis)
    analysis as HeapAnalysisSuccess
    assertThat(analysis.applicationLeaks).hasSize(1)
    val leak = analysis.applicationLeaks[0].leakTraces.first()
    val firstReference = leak.referencePath.first()
    assertThat(firstReference.originObject.className).isEqualTo("android.os.MessageQueue")
    assertThat(firstReference.referenceDisplayName).isEqualTo("[0]")
  }

  @Test fun `duplicated unloaded classes are ignored`() {
    val expectedDuplicatedClassNames = setOf(
      "leakcanary.internal.DebuggerControl",
      "shark.AndroidResourceIdNames\$Companion",
      "shark.GraphContext",
      "shark.AndroidResourceIdNames\$Companion\$readFromHeap$1",
      "leakcanary.internal.HeapDumpTrigger\$saveResourceIdNamesToMemory$1",
      "leakcanary.internal.HeapDumpTrigger\$saveResourceIdNamesToMemory$2",
      "shark.AndroidResourceIdNames",
      "leakcanary.internal.FutureResult",
      "leakcanary.internal.AndroidHeapDumper\$showToast$1",
      "android.widget.Toast\$TN",
      "android.widget.Toast\$TN$1",
      "android.widget.Toast\$TN$2",
      "leakcanary.internal.AndroidHeapDumper\$showToast$1$1",
      "com.squareup.leakcanary.core.R\$dimen",
      "com.squareup.leakcanary.core.R\$layout",
      "android.text.style.WrapTogetherSpan[]"
    )

    val file = "unloaded_classes-stripped.hprof".classpathFile()

    val header = HprofHeader.parseHeaderOf(file)

    val stickyClasses = mutableListOf<Long>()
    val classesAndNameStringId = mutableMapOf<Long, Long>()
    val stringRecordById = mutableMapOf<Long, String>()
    StreamingHprofReader.readerFor(file, header)
      .readRecords(setOf(ROOT_STICKY_CLASS, STRING_IN_UTF8, LOAD_CLASS)) { tag, length, reader ->
        when (tag) {
          ROOT_STICKY_CLASS -> reader.readStickyClassGcRootRecord().apply {
            stickyClasses += id
          }

          STRING_IN_UTF8 -> reader.readStringRecord(length).apply {
            stringRecordById[id] = string
          }

          LOAD_CLASS -> reader.readLoadClassRecord().apply {
            classesAndNameStringId[id] = classNameStringId
          }

          else -> {}
        }
      }
    val duplicatedClassObjectIdsByNameStringId =
      classesAndNameStringId.entries
        .groupBy { (_, className) -> className }
        .mapValues { (_, value) -> value.map { (key, _) -> key } }
        .filter { (_, values) -> values.size > 1 }

    val actualDuplicatedClassNames = duplicatedClassObjectIdsByNameStringId.keys
      .map { stringRecordById.getValue(it) }
      .toSet()
    assertThat(actualDuplicatedClassNames).isEqualTo(expectedDuplicatedClassNames)

    val duplicateRootClassObjectIdByClassName = duplicatedClassObjectIdsByNameStringId
      .mapKeys { (key, _) -> stringRecordById.getValue(key) }
      .mapValues { (_, value) -> value.single { it in stickyClasses } }

    file.openHeapGraph().use { graph ->
      val expectedDuplicatedRootClassObjectIds =
        duplicateRootClassObjectIdByClassName.values.toSortedSet()

      val actualDuplicatedRootClassObjectIds = duplicateRootClassObjectIdByClassName.keys
        .map { className ->
          graph.findClassByName(className)!!.objectId
        }
        .toSortedSet()

      assertThat(actualDuplicatedRootClassObjectIds).isEqualTo(
        expectedDuplicatedRootClassObjectIds
      )
    }
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
