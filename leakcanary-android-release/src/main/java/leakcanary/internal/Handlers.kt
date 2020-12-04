package leakcanary.internal

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process

internal fun startBackgroundHandlerThread(threadName: String): Handler {
  val thread = HandlerThread(threadName, Process.THREAD_PRIORITY_BACKGROUND)
  thread.start()
  return Handler(thread.looper)
}

internal val uiHandler = Handler(Looper.getMainLooper())