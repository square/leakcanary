package shark

import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph

/**
 * Measures the wall-clock overhead that [DominatorTree.runConvergenceLoop] adds to a full
 * dominator-tree analysis (BFS traversal + retained-size computation) on a real heap dump.
 *
 * The two variants are otherwise identical:
 *  - **without loop**: BFS → [DominatorTree.buildFullDominatorTree]
 *  - **with loop**:    BFS → [DominatorTree.runConvergenceLoop] → [DominatorTree.buildFullDominatorTree]
 *
 * Both variants create the [DominatorTree] with `collectCrossEdges = true` (the current
 * hardcoded default in [PrioritizingShortestPathFinder]) so the only measurable difference
 * is the cost of the convergence loop itself (plus a negligible difference in cross-edge
 * storage that is allocated but unused in the "without" case).
 *
 * Runs 4 iterations. The first warms the OS page cache and JIT; use the later runs for
 * stable numbers.
 */
@OptIn(ExperimentalTime::class)
class ConvergenceLoopBenchmark {

  @Test fun `convergence loop overhead - gcroot_unknown_object 25MB`() {
    val hprofFile = "gcroot_unknown_object.hprof".classpathFile()
    val ignoredRefs = emptyList<IgnoredReferenceMatcher>()

    fun runAnalysis(convergenceLoop: Boolean): Duration = measureTime {
      hprofFile.openHeapGraph().use { graph ->
        val pathFinder = PrioritizingShortestPathFinder.Factory(
          listener = {},
          referenceReaderFactory = ActualMatchingReferenceReaderFactory(emptyList()),
          gcRootProvider = MatchingGcRootProvider(ignoredRefs),
          computeRetainedHeapSize = true,
        ).createFor(graph)
        val result = pathFinder.findShortestPathsFromGcRoots(setOf())
        if (convergenceLoop) {
          result.dominatorTree!!.runConvergenceLoop()
        }
        result.dominatorTree!!.buildFullDominatorTree(AndroidObjectSizeCalculator(graph))
      }
    }

    println("\n=== ConvergenceLoopBenchmark (gcroot_unknown_object.hprof, 25 MB) ===")
    println("%-6s  %-20s  %-20s  %s".format("Run", "Without loop", "With loop", "Delta"))

    repeat(4) { i ->
      val withoutLoop = runAnalysis(convergenceLoop = false)
      val withLoop = runAnalysis(convergenceLoop = true)
      val delta = withLoop - withoutLoop
      val label = if (i == 0) "${i + 1} (warmup)" else "${i + 1}"
      println("%-6s  %-20s  %-20s  %s".format(label, withoutLoop, withLoop, delta))
    }
    println("===================================================================")
  }

  @Test fun `convergence loop diagnostics - gcroot_unknown_object 25MB`() {
    val hprofFile = "gcroot_unknown_object.hprof".classpathFile()
    val ignoredRefs = emptyList<IgnoredReferenceMatcher>()

    println("\n=== Convergence loop diagnostics ===")
    hprofFile.openHeapGraph().use { graph ->
      val pathFinder = PrioritizingShortestPathFinder.Factory(
        listener = {},
        referenceReaderFactory = ActualMatchingReferenceReaderFactory(emptyList()),
        gcRootProvider = MatchingGcRootProvider(ignoredRefs),
        computeRetainedHeapSize = true,
      ).createFor(graph)
      val result = pathFinder.findShortestPathsFromGcRoots(setOf())
      val tree = result.dominatorTree!!

      var iterations = 0
      val loopTime = measureTime {
        iterations = tree.runConvergenceLoop()
      }
      println("Convergence loop: $iterations iteration(s) in $loopTime")

      val retainedTime = measureTime {
        tree.buildFullDominatorTree(AndroidObjectSizeCalculator(graph))
      }
      println("buildFullDominatorTree: $retainedTime")
    }
    println("====================================")
  }
}
