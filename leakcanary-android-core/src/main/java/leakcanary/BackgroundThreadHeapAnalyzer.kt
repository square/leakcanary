package leakcanary

import android.os.Handler
import android.os.HandlerThread
import leakcanary.EventListener.Event
import leakcanary.EventListener.Event.HeapDump

/**
 * Starts heap analysis on a background [HandlerThread] when receiving a [HeapDump] event.
 */
object BackgroundThreadHeapAnalyzer : EventListener {

  private val backgroundHandler by lazy {
    val handlerThread = HandlerThread("HeapAnalyzer")
    handlerThread.start()
    Handler(handlerThread.looper)
  }

  override fun onEvent(event: Event) {
    if (event is HeapDump) {
      backgroundHandler.post {
        AndroidDebugHeapAnalyzer.runAnalysisBlocking(event)
      }
    }
  }
}
