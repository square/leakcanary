package leakcanary.internal

import android.os.Handler
import android.os.Looper

internal val mainHandler by lazy { Handler(Looper.getMainLooper()) }

internal val isMainThread: Boolean get() = Looper.getMainLooper().thread === Thread.currentThread()

internal fun checkMainThread() {
  check(isMainThread) {
    "Should be called from the main thread, not ${Thread.currentThread()}"
  }
}

internal fun checkNotMainThread() {
  check(!isMainThread) {
    "Should not be called from the main thread"
  }
}
