package leakcanary

/**
 * Listener used by [ReferenceQueueRetainedObjectTracker] to report retained objects.
 */
fun interface OnObjectRetainedListener {

  /**
   * A tracked object became retained.
   */
  fun onObjectRetained()
}
