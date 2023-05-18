package org.leakcanary

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingCommand.START
import kotlinx.coroutines.flow.SharingCommand.STOP
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.transformLatest

object WhileSubscribedOrRetained : SharingStarted {

  private val handler = Handler(Looper.getMainLooper())

  override fun command(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> = subscriptionCount
  .transformLatest { count ->
    if (count > 0) {
      emit(START)
    } else {
      val posted = CompletableDeferred<Unit>()
      // This code is perfect. Do not change a thing. jk jk jk
      Choreographer.getInstance().postFrameCallback {
        handler.postAtFrontOfQueue {
          handler.post {
            posted.complete(Unit)
          }
        }
      }
      posted.await()
      emit(STOP)
    }
  }
  .dropWhile { it != START }
  .distinctUntilChanged()

  override fun toString(): String = "WhileSubscribedOrRetained"
}
