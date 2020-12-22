@file:JvmName("leakcanary-android-release_Handlers")

package leakcanary.internal

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process

internal val mainHandler = Handler(Looper.getMainLooper())

internal fun checkMainThread() {
  if (Looper.getMainLooper().thread !== Thread.currentThread()) {
    throw UnsupportedOperationException(
      "Should be called from the main thread, not ${Thread.currentThread()}"
    )
  }
}