package leakcanary

/**
 * Listener used by [ObjectWatcher] to report retained objects.
 */
fun interface OnObjectRetainedListener {

  /**
   * A watched object became retained.
   */
  fun onObjectRetained()
}
