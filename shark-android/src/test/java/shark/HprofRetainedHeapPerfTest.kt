package shark

import org.assertj.core.api.AbstractIntegerAssert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.AndroidReferenceMatchers.Companion.buildKnownReferences
import shark.AndroidReferenceMatchers.FINALIZER_WATCHDOG_DAEMON
import shark.AndroidReferenceMatchers.REFERENCES
import shark.GcRoot.ThreadObject
import shark.OnAnalysisProgressListener.Step.COMPUTING_NATIVE_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.COMPUTING_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.EXTRACTING_METADATA
import shark.OnAnalysisProgressListener.Step.FINDING_DOMINATORS
import shark.OnAnalysisProgressListener.Step.FINDING_PATHS_TO_RETAINED_OBJECTS
import shark.OnAnalysisProgressListener.Step.FINDING_RETAINED_OBJECTS
import shark.OnAnalysisProgressListener.Step.INSPECTING_OBJECTS
import shark.OnAnalysisProgressListener.Step.PARSING_HEAP_DUMP
import java.io.File
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.math.absoluteValue
import kotlin.reflect.KClass

private const val ANALYSIS_THREAD = "analysis"

class HprofRetainedHeapPerfTest {

  @get:Rule
  var tmpFolder = TemporaryFolder()

  lateinit var folder: File

  @Before
  fun setUp() {
    folder = tmpFolder.newFolder()
  }

  @Test fun `freeze retained memory when indexing leak_asynctask_o`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()

    val (baselineHeap, heapWithIndex) = runInThread(ANALYSIS_THREAD) {
      val baselineHeap = dumpHeap("baseline")
      val hprofIndex = indexRecordsOf(hprofFile)
      val heapWithIndex = dumpHeapRetaining(hprofIndex)
      baselineHeap to heapWithIndex
    }

    val retained =
      heapWithIndex.retainedHeap(ANALYSIS_THREAD) - baselineHeap.retainedHeap(ANALYSIS_THREAD)

    assertThat(retained).isEqualTo(4.8 MB +-5 % margin)
  }

  @Test fun `freeze retained memory when indexing leak_asynctask_m`() {
    val hprofFile = "leak_asynctask_m.hprof".classpathFile()

    val (baselineHeap, heapWithIndex) = runInThread(ANALYSIS_THREAD) {
      val baselineHeap = dumpHeap("baseline")
      val hprofIndex = indexRecordsOf(hprofFile)
      val heapWithIndex = dumpHeapRetaining(hprofIndex)
      baselineHeap to heapWithIndex
    }

    val retained =
      heapWithIndex.retainedHeap(ANALYSIS_THREAD) - baselineHeap.retainedHeap(ANALYSIS_THREAD)

    assertThat(retained).isEqualTo(8.2 MB +-5 % margin)
  }

  @Test fun `freeze retained memory through analysis steps of leak_asynctask_o`() {
    val hprofFile = "leak_asynctask_o.hprof".classpathFile()
    val stepsToHeapDumpFile = mutableMapOf<OnAnalysisProgressListener.Step, File>()
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener { step ->
      stepsToHeapDumpFile[step] = dumpHeap(step.name)
    })

    // matchers contain large description strings which depending on the VM maybe be reachable
    // only via matchers (=> thread locals), and otherwise also statically by the enum class that
    // defines them. So we create a reference outside of the working thread to exclude them from
    // the retained count and avoid a varying count.
    val matchers = AndroidReferenceMatchers.appDefaults
    val baselineHeap = runInThread(ANALYSIS_THREAD) {
      val baselineHeap = dumpHeap("baseline")
      heapAnalyzer.analyze(
          heapDumpFile = hprofFile,
          leakingObjectFinder = FilteringLeakingObjectFinder(
              AndroidObjectInspectors.appLeakingObjectFilters
          ),
          referenceMatchers = matchers,
          objectInspectors = AndroidObjectInspectors.appDefaults,
          metadataExtractor = AndroidMetadataExtractor,
          computeRetainedHeapSize = true
      ).apply {
        check(this is HeapAnalysisSuccess) {
          "Expected success not $this"
        }
      }
      baselineHeap
    }

    val retainedBeforeAnalysis = baselineHeap.retainedHeap(ANALYSIS_THREAD)
    val retained = stepsToHeapDumpFile.mapValues {
      it.value.retainedHeap(ANALYSIS_THREAD) - retainedBeforeAnalysis
    }

    assertThat(retained after PARSING_HEAP_DUMP).isEqualTo(4.70 MB +-5 % margin)
    assertThat(retained after EXTRACTING_METADATA).isEqualTo(4.75 MB +-5 % margin)
    assertThat(retained after FINDING_RETAINED_OBJECTS).isEqualTo(4.85 MB +-5 % margin)
    assertThat(retained after FINDING_PATHS_TO_RETAINED_OBJECTS).isEqualTo(6.25 MB +-5 % margin)
    assertThat(retained after FINDING_DOMINATORS).isEqualTo(6.25 MB +-5 % margin)
    assertThat(retained after INSPECTING_OBJECTS).isEqualTo(6.26 MB +-5 % margin)
    assertThat(retained after COMPUTING_NATIVE_RETAINED_SIZE).isEqualTo(6.26 MB +-5 % margin)
    assertThat(retained after COMPUTING_RETAINED_SIZE).isEqualTo(5.18 MB +-5 % margin)
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
    // Dumps the heap in a separate thread to avoid java locals being added to the count of
    // bytes retained by this thread.
    return runInThread("heap dump") {
      val testHprofFile = File(folder, "$name.hprof")
      if (testHprofFile.exists()) {
        testHprofFile.delete()
      }
      JvmTestHeapDumper.dumpHeap(testHprofFile.absolutePath)
      testHprofFile
    }
  }

  private fun <T : Any> runInThread(
    threadName: String,
    work: () -> T
  ): T {
    lateinit var result: T
    val latch = CountDownLatch(1)
    Thread {
      result = work()
      latch.countDown()
    }.apply {
      name = threadName
      start()
    }
    check(latch.await(30, SECONDS))
    return result
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

  private fun File.retainedHeap(threadName: String): Bytes {
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
    val analysis = heapAnalyzer.analyze(
        heapDumpFile = this,
        referenceMatchers = buildKnownReferences(EnumSet.of(REFERENCES, FINALIZER_WATCHDOG_DAEMON)),
        leakingObjectFinder = LeakingObjectFinder { graph ->
          setOf(graph.gcRoots.first { gcRoot ->
            gcRoot is ThreadObject &&
                graph.objectExists(gcRoot.id) &&
                graph.findObjectById(gcRoot.id)
                    .asInstance!!["java.lang.Thread", "name"]!!
                    .value.readAsJavaString() == threadName
          }.id)
        },
        computeRetainedHeapSize = true
    )
    check(analysis is HeapAnalysisSuccess) {
      "Expected success not $analysis"
    }
    return analysis.applicationLeaks.single().leakTraces.single().retainedHeapByteSize!!.bytes
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