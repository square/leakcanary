package leakcanary

import java.io.File
import shark.HeapDiff
import shark.HeapTraversalInput
import shark.HeapTraversalOutput
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.InitialState
import shark.ObjectGrowthDetector
import shark.RepeatingScenarioObjectGrowthDetector
import shark.SharkLog

/**
 * A [RepeatingScenarioObjectGrowthDetector] suitable for junit based automated tests that
 * can dump the heap.
 *
 * @see [RepeatingScenarioObjectGrowthDetector.findRepeatedlyGrowingObjects]
 */
class DumpingRepeatingScenarioObjectGrowthDetector(
  private val objectGrowthDetector: ObjectGrowthDetector,
  private val heapDumpFileProvider: HeapDumpFileProvider,
  private val heapDumper: HeapDumper,
  private val heapDumpStorageStrategy: HeapDumpStorageStrategy,
) : RepeatingScenarioObjectGrowthDetector {

  override fun findRepeatedlyGrowingObjects(
    maxHeapDumps: Int,
    scenarioLoopsPerDump: Int,
    roundTripScenario: () -> Unit
  ): HeapDiff {
    val heapDiff = try {
      findRepeatedlyGrowingObjectsInner(scenarioLoopsPerDump, maxHeapDumps, roundTripScenario)
    } catch (exception: Throwable) {
      heapDumpStorageStrategy.onHeapDiffResult(Result.failure(exception))
      throw exception
    }
    heapDumpStorageStrategy.onHeapDiffResult(Result.success(heapDiff))
    return heapDiff
  }

  private fun findRepeatedlyGrowingObjectsInner(
    scenarioLoopsPerDump: Int,
    maxHeapDumps: Int,
    roundTripScenario: () -> Unit
  ): HeapDiff {
    var lastTraversalOutput: HeapTraversalInput = InitialState(scenarioLoopsPerDump)
    for (i in 1..maxHeapDumps) {
      repeat(scenarioLoopsPerDump) {
        roundTripScenario()
      }
      val heapDumpFile = heapDumpFileProvider.newHeapDumpFile()
      heapDumper.dumpHeap(heapDumpFile)
      check(heapDumpFile.exists()) {
        "Expected file to exist after heap dump: ${heapDumpFile.absolutePath}"
      }
      heapDumpStorageStrategy.onHeapDumped(heapDumpFile)
      lastTraversalOutput = try {
        heapDumpFile.findGrowingObjects(lastTraversalOutput)
      } finally {
        heapDumpStorageStrategy.onHeapDumpClosed(heapDumpFile)
      }
      if (lastTraversalOutput is HeapDiff) {
        if (!lastTraversalOutput.isGrowing) {
          return lastTraversalOutput
        } else if (i < maxHeapDumps) {
          // Log unless it's the last diff, which typically gets printed by calling code.
          SharkLog.d {
            "After ${lastTraversalOutput.traversalCount} heap dumps with $scenarioLoopsPerDump scenario iterations before each, " +
              "${lastTraversalOutput.growingObjects.size} growing nodes:\n" + lastTraversalOutput.growingObjects
          }
        }
      }
    }
    check(lastTraversalOutput is HeapDiff) {
      "Final output should be a HeapGrowth, traversalCount ${lastTraversalOutput.traversalCount - 1} " +
        "should be >= 2. Output: $lastTraversalOutput"
    }
    return lastTraversalOutput
  }

  private fun File.findGrowingObjects(
    previousTraversal: HeapTraversalInput
  ): HeapTraversalOutput {
    return openHeapGraph().use { heapGraph ->
      objectGrowthDetector.findGrowingObjects(
        heapGraph = heapGraph,
        previousTraversal = previousTraversal,
      )
    }
  }
}

