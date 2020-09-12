package shark

import org.assertj.core.api.AbstractIntegerAssert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.AndroidReferenceMatchers.Companion.buildKnownReferences
import shark.AndroidReferenceMatchers.FINALIZER_WATCHDOG_DAEMON
import shark.AndroidReferenceMatchers.REFERENCES
import shark.HeapObject.HeapClass
import shark.OnAnalysisProgressListener.Step.COMPUTING_NATIVE_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.COMPUTING_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.EXTRACTING_METADATA
import shark.OnAnalysisProgressListener.Step.FINDING_PATHS_TO_RETAINED_OBJECTS
import shark.OnAnalysisProgressListener.Step.FINDING_RETAINED_OBJECTS
import shark.OnAnalysisProgressListener.Step.PARSING_HEAP_DUMP
import java.io.File
import java.util.EnumSet
import kotlin.math.absoluteValue
import kotlin.reflect.KClass

class HprofRetainedHeapPerfTest {

  @get:Rule
  var tmpFolder = TemporaryFolder()

  @Test fun `freeze retained memory when indexing leak_asynctask_o`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val baselineHeap = dumpHeap("baseline")

    val hprofIndex = indexRecordsOf(hprofFile)

    val heapWithIndex = dumpHeapRetaining(hprofIndex)

    val retained = heapWithIndex.retainedHeap() - baselineHeap.retainedHeap()

    assertThat(retained).isEqualTo(4.8 MB +-5 % margin)
  }

  @Test fun `freeze retained memory when indexing leak_asynctask_m`() {
    val hprofFile = "leak_asynctask_m.hprof".classpathFile()

    val baselineHeap = dumpHeap("baseline")

    val hprofIndex = indexRecordsOf(hprofFile)

    val heapWithIndex = dumpHeapRetaining(hprofIndex)

    val retained = heapWithIndex.retainedHeap() - baselineHeap.retainedHeap()

    assertThat(retained).isEqualTo(8.2 MB +-5 % margin)
  }

  @Test fun `freeze retained memory through analysis steps of leak_asynctask_o`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()
    val stepsToHeapDumpFile = mutableMapOf<OnAnalysisProgressListener.Step, File>()
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener { step ->
      stepsToHeapDumpFile[step] = dumpHeap(step.name)
    })
    val baselineHeap = dumpHeap("baseline")

    heapAnalyzer.analyze(
        heapDumpFile = hprofFile,
        leakingObjectFinder = FilteringLeakingObjectFinder(
            AndroidObjectInspectors.appLeakingObjectFilters
        ),
        referenceMatchers = AndroidReferenceMatchers.appDefaults,
        objectInspectors = AndroidObjectInspectors.appDefaults,
        metadataExtractor = AndroidMetadataExtractor,
        computeRetainedHeapSize = true
    ).apply {
      check(this is HeapAnalysisSuccess) {
        "Expected success not $this"
      }
    }

    val retainedBeforeAnalysis = baselineHeap.retainedHeap()
    val retained =
      stepsToHeapDumpFile.mapValues { it.value.retainedHeap() - retainedBeforeAnalysis }

    assertThat(retained after PARSING_HEAP_DUMP).isEqualTo(4.9 MB +-10 % margin)
    assertThat(retained after EXTRACTING_METADATA).isEqualTo(4.9 MB +-10 % margin)
    assertThat(retained after FINDING_RETAINED_OBJECTS).isEqualTo(5 MB +-10 % margin)
    assertThat(retained after FINDING_PATHS_TO_RETAINED_OBJECTS).isEqualTo(5.4 MB +-10 % margin)
    assertThat(retained after COMPUTING_NATIVE_RETAINED_SIZE).isEqualTo(5.4 MB +-10 % margin)
    assertThat(retained after COMPUTING_RETAINED_SIZE).isEqualTo(5.4 MB +-10 % margin)
  }

  private fun indexRecordsOf(hprofFile: File): HprofIndex {
    return HprofIndex.indexRecordsOf(
        hprofSourceProvider = FileSourceProvider(hprofFile),
        hprofHeader = HprofHeader.parseHeaderOf(hprofFile)
    )
  }

  private fun dumpHeapRetaining(instance: Any): File {
    val heapDumpFile = dumpHeap("retaining-${instance::class.java.name}")
    // Dumb check to prevent instance from being garbage collected.
    check(instance::class::class.isInstance(KClass::class))
    return heapDumpFile
  }

  private fun dumpHeap(name: String): File {
    val testHprofFile = File(tmpFolder.newFolder(), "$name.hprof")
    if (testHprofFile.exists()) {
      testHprofFile.delete()
    }
    JvmTestHeapDumper.dumpHeap(testHprofFile.absolutePath)
    return testHprofFile
  }

  private infix fun Map<OnAnalysisProgressListener.Step, Bytes>.after(step: OnAnalysisProgressListener.Step): Bytes {
    val values = OnAnalysisProgressListener.Step.values()
    for (nextOrdinal in step.ordinal + 1 until values.size) {
      val nextStepRetained = this[values[nextOrdinal]]
      if (nextStepRetained != null) {
        return nextStepRetained
      }
    }
    error("No step in $this after $step")
  }

  private fun File.retainedHeap(): Bytes {
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = heapAnalyzer.analyze(
        heapDumpFile = this,
        referenceMatchers = buildKnownReferences(EnumSet.of(REFERENCES, FINALIZER_WATCHDOG_DAEMON)),
        leakingObjectFinder = LeakingObjectFinder { graph ->
          graph.gcRoots
              .filter { gcRoot ->
                graph.objectExists(gcRoot.id)
              }.filter {
                graph.findObjectById(it.id) !is HeapClass
              }.map { it.id }.toSet()
        },
        computeRetainedHeapSize = true
    )
    check(analysis is HeapAnalysisSuccess) {
      "Expected success not $analysis"
    }
    return analysis.applicationLeaks
        .flatMap { it.leakTraces }
        .sumBy {
          it.retainedHeapByteSize!!
        }.bytes
  }

  class BytesAssert(bytes: Bytes) : AbstractIntegerAssert<BytesAssert>(
      bytes.count, BytesAssert::class.java
  ) {
    fun isEqualTo(expected: BytesWithError): BytesAssert {
      val errorPercentage = expected.error.percentage.absoluteValue
      return isBetween(
          (expected.count * (1 - errorPercentage)).toInt(),
          (expected.count * (1 + errorPercentage)).toInt()
      )
    }
  }

  private fun assertThat(bytes: Bytes?) = BytesAssert(bytes!!)

  data class Bytes(val count: Int)

  operator fun Bytes.minus(other: Bytes) = Bytes(count - other.count)

  private val Int.bytes: Bytes
    get() = Bytes(this)

  class BytesWithError(
    val count: Int,
    val error: ErrorPercentage
  )

  object Margin

  private val margin
    get() = Margin

  class ErrorPercentage(val percentage: Double)

  infix fun Double.MB(error: ErrorPercentage) =
    BytesWithError((this * 1_000_000).toInt(), error)

  infix fun Int.MB(error: ErrorPercentage) =
    BytesWithError(this * 1_000_000, error)

  operator fun Int.rem(ignored: Margin): ErrorPercentage = ErrorPercentage(this / 100.0)

}