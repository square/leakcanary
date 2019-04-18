package leakcanary.internal

import leakcanary.AnalyzerProgressListener
import leakcanary.AnalyzerProgressListener.Step
import leakcanary.CanaryLog
import leakcanary.HeapAnalysisSuccess
import leakcanary.HeapDump
import leakcanary.LeakingInstance
import leakcanary.experimental.HeapAnalyzer
import org.junit.Test

class HeapAnalysisTest {

  @Test
  fun theWholeThing() {
    CanaryLog.logger = object : CanaryLog.Logger {
      override fun d(
        message: String,
        vararg args: Any?
      ) {
        println(String.format(message, *args))
      }

      override fun d(
        throwable: Throwable?,
        message: String,
        vararg args: Any?
      ) {
        throwable!!.printStackTrace()
      }

    }
    var time = System.nanoTime()
    val file = fileFromName(HeapDumpFile.MULTIPLE_LEAKS.filename)
    val heapAnalyzer = HeapAnalyzer(object : AnalyzerProgressListener {
      override fun onProgressUpdate(step: Step) {
        val now = System.nanoTime()
        val elapsed = (now - time) / 1000000
        time = now
        println("New step $step, last step done after $elapsed ms")
      }
    })

    val heapDump = HeapDump.builder(file)
        .excludedRefs(defaultExcludedRefs.build())
        .build()
    val leaks = heapAnalyzer.checkForLeaks(heapDump)

    val now = System.nanoTime()
    val elapsed = (now - time) / 1000000
    println("Last step done after $elapsed ms")

    println("result: $leaks")

    require(leaks is HeapAnalysisSuccess)
    require(leaks.retainedInstances.size == 5)
    leaks.retainedInstances.forEach {
      require(it is LeakingInstance) {
        "$it was expected to be a LeakingInstance"
      }
    }
  }
}