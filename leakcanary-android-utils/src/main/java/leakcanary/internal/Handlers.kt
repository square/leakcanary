package leakcanary.internal

import android.os.Handler
import android.os.Looper

internal val mainHandler by lazy { Handler(Looper.getMainLooper()) }

internal fun checkMainThread() {
  check(Looper.getMainLooper().thread === Thread.currentThread()) {
    "Should be called from the main thread, not ${Thread.currentThread()}"
  }
}

internal fun checkNotMainThread() {
  check(Looper.getMainLooper().thread !== Thread.currentThread()) {
    "Should not be called from the main thread"
  }
}