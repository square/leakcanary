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
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.OnAnalysisProgressListener.Step.COMPUTING_NATIVE_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.COMPUTING_RETAINED_SIZE
import shark.OnAnalysisProgressListener.Step.EXTRACTING_METADATA
import shark.OnAnalysisProgressListener.Step.FINDING_DOMINATORS
import shark.OnAnalysisProgressListener.Step.FINDING_PATHS_TO_RETAINED_OBJECTS
import shark.OnAnalysisProgressListener.Step.FINDING_RETAINED_OBJECTS
import shark.OnAnalysisProgressListener.Step.INSPECTING_OBJECTS
import shark.OnAnalysisProgressListener.Step.PARSING_HEAP_DUMP
import shark.internal.ObjectDominators
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

    val (analysisRetained, dominators) = heapWithIndex.retainedHeap(ANALYSIS_THREAD)

    val retained = analysisRetained - baselineHeap.retainedHeap(ANALYSIS_THREAD).first

    assertThat(retained).isEqualTo(5.07 MB +-5 % margin)
  }

  @Test fun `freeze retained memory when indexing leak_asynctask_m`() {
    val hprofFile = "leak_asynctask_m.hprof".classpathFile()

    val (baselineHeap, heapWithIndex) = runInThread(ANALYSIS_THREAD) {
      val baselineHeap = dumpHeap("baseline")
      val hprofIndex = indexRecordsOf(hprofFile)
      val heapWithIndex = dumpHeapRetaining(hprofIndex)
      baselineHeap to heapWithIndex
    }

    val (analysisRetained, dominators) = heapWithIndex.retainedHeap(ANALYSIS_THREAD)

    val retained = analysisRetained - baselineHeap.retainedHeap(ANALYSIS_THREAD).first

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

    val retainedBeforeAnalysis = baselineHeap.retainedHeap(ANALYSIS_THREAD).first
    val retained = stepsToHeapDumpFile.mapValues {
      val retainedPair = it.value.retainedHeap(ANALYSIS_THREAD, computeDominators = true)
      retainedPair.first - retainedBeforeAnalysis to retainedPair.second
    }

    assertThat(retained after PARSING_HEAP_DUMP).isEqualTo(5.07 MB +-5 % margin)
    assertThat(retained after EXTRACTING_METADATA).isEqualTo(5.12 MB +-5 % margin)
    assertThat(retained after FINDING_RETAINED_OBJECTS).isEqualTo(5.22 MB +-5 % margin)
    assertThat(retained after FINDING_PATHS_TO_RETAINED_OBJECTS).isEqualTo(6.62 MB +-5 % margin)
    assertThat(retained after FINDING_DOMINATORS).isEqualTo(6.62 MB +-5 % margin)
    assertThat(retained after INSPECTING_OBJECTS).isEqualTo(6.63 MB +-5 % margin)
    assertThat(retained after COMPUTING_NATIVE_RETAINED_SIZE).isEqualTo(6.63 MB +-5 % margin)
    assertThat(retained after COMPUTING_RETAINED_SIZE).isEqualTo(5.55 MB +-5 % margin)
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

  private infix fun Map<OnAnalysisProgressListener.Step, Pair<Bytes, String>>.after(step: OnAnalysisProgressListener.Step): Pair<Bytes, String> {
    val values = OnAnalysisProgressListener.Step.values()
    for (nextOrdinal in step.ordinal + 1 until values.size) {
      val pair = this[values[nextOrdinal]]
      if (pair != null) {
        val (nextStepRetained, dominatorTree) = pair

        return nextStepRetained to "\n$nextStepRetained retained by analysis thread after step ${step.name} not valid\n" + dominatorTree
      }
    }
    error("No step in $this after $step")
  }

  private fun File.retainedHeap(
    threadName: String,
    computeDominators: Boolean = false
  ): Pair<Bytes, String> {
    val heapAnalyzer = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)

    val (analysis, dominatorTree) = openHeapGraph().use { graph ->
      val analysis = heapAnalyzer.analyze(
          heapDumpFile = this,
          graph = graph,
          referenceMatchers = buildKnownReferences(
              EnumSet.of(REFERENCES, FINALIZER_WATCHDOG_DAEMON)
          ),
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

      val dominatorTree = if (computeDominators) {
        val weakAndFinalizerRefs = EnumSet.of(REFERENCES, FINALIZER_WATCHDOG_DAEMON)
        val ignoredRefs = buildKnownReferences(weakAndFinalizerRefs).map { matcher ->
          matcher as IgnoredReferenceMatcher
        }
        ObjectDominators().renderDominatorTree(
            graph, ignoredRefs, 200, threadName, true
        )
      } else ""
      analysis to dominatorTree
    }

    return analysis.applicationLeaks.single().leakTraces.single().retainedHeapByteSize!!.bytes to dominatorTree
  }

  class BytesAssert(
    bytes: Bytes,
    description: String
  ) : AbstractIntegerAssert<BytesAssert>(
      bytes.count, BytesAssert::class.java
  ) {

    init {
      describedAs(description)
    }

    fun isEqualTo(expected: BytesWithError): BytesAssert {
      val errorPercentage = expected.error.percentage.absoluteValue
      return isBetween(
          (expected.count * (1 - errorPercentage)).toInt(),
          (expected.count * (1 + errorPercentage)).toInt()
      )
    }
  }

  private fun assertThat(bytes: Bytes) = BytesAssert(bytes, "")

  private fun assertThat(pair: Pair<Bytes, String>) = BytesAssert(pair.first, pair.second)

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