package leakcanary

import android.os.Handler
import android.os.HandlerThread
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.AndroidDebugHeapAnalyzer
import leakcanary.internal.InternalLeakCanary

/**
 * Starts heap analysis on a background [HandlerThread] when receiving a [HeapDump] event.
 */
object BackgroundThreadHeapAnalyzer : EventListener {

  internal val heapAnalyzerThreadHandler by lazy {
    val handlerThread = HandlerThread("HeapAnalyzer")
    handlerThread.start()
    Handler(handlerThread.looper)
  }

  override fun onEvent(event: Event) {
    if (event is HeapDump) {
      heapAnalyzerThreadHandler.post {
        val doneEvent = AndroidDebugHeapAnalyzer.runAnalysisBlocking(event) { event ->
          InternalLeakCanary.sendEvent(event)
        }
        InternalLeakCanary.sendEvent(doneEvent)
      }
    }
  }
}
