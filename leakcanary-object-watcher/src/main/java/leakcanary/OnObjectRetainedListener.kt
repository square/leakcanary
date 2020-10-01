package leakcanary

/**
 * Listener used by [ObjectWatcher] to report retained objects.
 *
 * This is a functional interface with which you can create a [OnObjectRetainedListener] from a lambda.
 */
fun interface OnObjectRetainedListener {

  /**
   * A watched object became retained.
   */
  fun onObjectRetained()

  companion object {

    @Deprecated("Leverage Kotlin SAM lambda expression")
    inline operator fun invoke(crossinline block: () -> Unit): OnObjectRetainedListener =
      object : OnObjectRetainedListener {
        override fun onObjectRetained() {
          block()
        }
      }
  }
}