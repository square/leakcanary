package leakcanary

import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.HeapAnalysisDone
import leakcanary.EventListener.Event.HeapAnalysisProgress
import leakcanary.EventListener.Event.HeapDumpFailed
import leakcanary.internal.HeapDumpTrigger
import leakcanary.internal.activity.screen.LeakTraceWrapper
import shark.SharkLog

object LogcatEventListener : EventListener {

  override fun onEvent(event: Event) {
    when(event) {
      is HeapDumpFailed -> {
        if (event.willRetryLater) {
          SharkLog.d(event.exception) { "Failed to dump heap, will retry in ${HeapDumpTrigger.WAIT_AFTER_DUMP_FAILED_MILLIS} ms" }
        } else {
          SharkLog.d(event.exception) { "Failed to dump heap, will not automatically retry" }
        }
      }
      is HeapAnalysisProgress -> {
        val percent =  (event.progressPercent * 100).toInt()
        SharkLog.d { "Analysis in progress, $percent% done, working on ${event.step.humanReadableName}" }
      }
      is HeapAnalysisDone<*> ->  {
        SharkLog.d { "\u200B\n${LeakTraceWrapper.wrap(event.heapAnalysis.toString(), 120)}" }
      }
    }
  }
}
