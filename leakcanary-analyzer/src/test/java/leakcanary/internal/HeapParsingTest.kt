package leakcanary.internal

import leakcanary.AnalyzerProgressListener
import leakcanary.AnalyzerProgressListener.Step
import leakcanary.updated.HeapAnalysisSuccess
import leakcanary.updated.HeapAnalyzer
import leakcanary.updated.HeapDump
import org.junit.Test

class HeapParsingTest {

  @Test
  fun theWholeThing() {
    var time = System.nanoTime()
    val file = fileFromName(HeapDumpFile.MULTIPLE_LEAKS.filename)
    val heapAnalyzer = HeapAnalyzer(object : AnalyzerProgressListener {
      override fun onProgressUpdate(step: Step) {
        val now = System.nanoTime()
        val elapsed = (now - time) / 1000000
        time = now
        println("now $step, last step done after $elapsed ms")
      }
    })

    val heapDump = HeapDump.builder(file)
        .excludedRefs(defaultExcludedRefs.build())
        .build()
    val leaks = heapAnalyzer.checkForLeaks(heapDump)

    val now = System.nanoTime()
    val elapsed = (now - time) / 1000000
    println("Last step done after $elapsed ms")

    require(leaks is HeapAnalysisSuccess)
    require(leaks.retainedInstances.size == 5)
  }
}