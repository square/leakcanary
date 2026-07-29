package shark

/**
 * Asked by the work reading a heap dump whether it should stop, which is how that work is canceled
 * from another thread.
 *
 * Shark asks on every read of the heap dump, and at the few points in an analysis that run for a
 * while without reading anything, then throws [CanceledException] as soon as a reason comes back.
 * Calling code therefore doesn't have to check anything itself: hand a signal to
 * [HprofHeapGraph.Companion.openHeapGraph] and the work built on top of that graph stops shortly
 * after it's asked to.
 *
 * Cancellation is cooperative on purpose. The other way to stop a thread is to interrupt it, and
 * that can't be used here: heap dump reads go through a [java.nio.channels.FileChannel], which
 * closes itself when a thread blocked on it is interrupted. Interrupting one of the threads reading
 * a heap dump would therefore leave that heap dump unreadable for all of them.
 */
fun interface CancelSignal {

  /**
   * Returns why the work should stop, or null if it should carry on. The reason becomes the message
   * of the [CanceledException] that stops the work, so it should say what canceled and, where that's
   * known, how far the work had got.
   *
   * Called from whichever thread is doing the work, from all of them when the work is spread over
   * several threads, and at least once per heap dump read, so implementations have to be cheap and
   * safe to call concurrently.
   */
  fun cancelReasonOrNull(): String?

  companion object {
    /** A [CancelSignal] for work that is never canceled. */
    val NEVER = CancelSignal { null }
  }
}

/**
 * Throws [CanceledException] if [CancelSignal.cancelReasonOrNull] returns a reason, and returns
 * normally otherwise. This is what Shark calls at each point the work it's doing can stop at.
 */
fun CancelSignal.throwIfCanceled() {
  val cancelReason = cancelReasonOrNull()
  if (cancelReason != null) {
    throw CanceledException(cancelReason)
  }
}
