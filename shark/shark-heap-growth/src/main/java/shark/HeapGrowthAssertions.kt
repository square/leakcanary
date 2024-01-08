package shark

import shark.DiffingHeapGrowthDetector.HeapDumpAfterLoopingScenario

fun ScenarioLoopHeapDumper.assertNoHeapGrowth(loopingScenario: () -> Unit) {
  sequenceOfHeapDumps(loopingScenario).assertNoHeapGrowth()
}

fun Sequence<HeapDumpAfterLoopingScenario>.assertNoHeapGrowth() {
  val detector = LoopingHeapGrowthDetector(DiffingHeapGrowthDetector())
  val heapDiff = detector.repeatDiffsWhileGrowing(this)
  if (!heapDiff.growing) {
    SharkLog.d { "Success, no more constantly growing nodes" }
  } else {
    val resultString =
      heapDiff.growingNodes.joinToString(separator = "##################\n") { leafNode ->
        leafNode.pathFromRootAsString()
      }
    throw AssertionError("Repeated heap growth detected, leak roots:\n$resultString")
  }
}





