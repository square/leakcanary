package shark

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.HprofHeapGraph.Companion.openHeapGraph

class CancelHeapAnalysisTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private var cancelReason: String? = null

  private val cancelSignal = CancelSignal { cancelReason }

  @Test fun `a canceled analysis throws instead of returning a failure`() {
    val hprofFile = dumpHeapWithLeak()

    hprofFile.openHeapGraph(cancelSignal = cancelSignal).use { graph ->
      cancelReason = CANCEL_REASON

      assertThatThrownBy { graph.analyze(hprofFile) }
        .isInstanceOf(CanceledException::class.java)
        .hasMessage(CANCEL_REASON)
    }
  }

  @Test fun `an analysis that is not canceled succeeds`() {
    val hprofFile = dumpHeapWithLeak()

    hprofFile.openHeapGraph(cancelSignal = cancelSignal).use { graph ->
      assertThat(graph.analyze(hprofFile)).isInstanceOf(HeapAnalysisSuccess::class.java)
    }
  }

  @Test fun `a failing analysis is still reported as a failure`() {
    val hprofFile = dumpHeapWithLeak()

    hprofFile.openHeapGraph(cancelSignal = cancelSignal).use { graph ->
      val analysis = graph.analyze(hprofFile, leakingObjectFinder = {
        throw UnsupportedOperationException("Not a cancellation")
      })

      assertThat(analysis).isInstanceOf(HeapAnalysisFailure::class.java)
      assertThat((analysis as HeapAnalysisFailure).exception.cause)
        .isInstanceOf(UnsupportedOperationException::class.java)
    }
  }

  /**
   * Walking the dominator tree once it's built reads the heap dump only to size the objects in it, so
   * with a size calculator that doesn't read, the loops accumulating retained sizes up the tree are
   * all that's left to notice a cancel.
   */
  @Test fun `building dominator nodes is canceled without reading the heap dump`() {
    val hprofFile = dumpHeapWithLeak()

    hprofFile.openHeapGraph(cancelSignal = cancelSignal).use { graph ->
      val tree = HeapDominatorTree.buildFor(
        graph = graph,
        referenceReader = ActualMatchingReferenceReaderFactory(emptyList()).createFor(graph),
        gcRootProvider = MatchingGcRootProvider(emptyList())
      )

      cancelReason = CANCEL_REASON

      assertThatThrownBy { tree.buildNodes(ObjectSizeCalculator { FAKE_OBJECT_SIZE }) }
        .isInstanceOf(CanceledException::class.java)
        .hasMessage(CANCEL_REASON)
    }
  }

  @Test fun `building dominator nodes that is not canceled sizes every reachable object`() {
    val hprofFile = dumpHeapWithLeak()

    hprofFile.openHeapGraph(cancelSignal = cancelSignal).use { graph ->
      val tree = HeapDominatorTree.buildFor(
        graph = graph,
        referenceReader = ActualMatchingReferenceReaderFactory(emptyList()).createFor(graph),
        gcRootProvider = MatchingGcRootProvider(emptyList())
      )

      val nodes = tree.buildNodes(ObjectSizeCalculator { FAKE_OBJECT_SIZE })

      assertThat(nodes.getValue(ValueHolder.NULL_REFERENCE).retainedCount)
        .isEqualTo(tree.reachableObjectCount)
    }
  }

  private fun HeapGraph.analyze(
    hprofFile: File,
    leakingObjectFinder: LeakingObjectFinder = KeyedWeakReferenceFinder
  ): HeapAnalysis {
    return HeapAnalyzer(OnAnalysisProgressListener.NO_OP).analyze(
      heapDumpFile = hprofFile,
      graph = this,
      leakingObjectFinder = leakingObjectFinder,
      computeRetainedHeapSize = true
    )
  }

  private fun dumpHeapWithLeak(): File {
    val hprofFile = testFolder.newFile("cancel-analysis.hprof")
    hprofFile.writeSinglePathToInstance()
    return hprofFile
  }

  companion object {
    private const val CANCEL_REASON = "canceled by a test"
    private const val FAKE_OBJECT_SIZE = 1L
  }
}
