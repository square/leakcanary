package org.leakcanary.util

import android.os.Handler
import android.os.Looper

val mainHandler by lazy { Handler(Looper.getMainLooper()) }

val isMainThread: Boolean get() = Looper.getMainLooper().thread === Thread.currentThread()

fun checkMainThread() {
  check(isMainThread) {
    "Should be called from the main thread, not ${Thread.currentThread()}"
  }
}

fun checkNotMainThread() {
  check(!isMainThread) {
    "Should not be called from the main thread"
  }
}
