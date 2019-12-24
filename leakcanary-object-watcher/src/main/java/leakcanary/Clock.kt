package leakcanary

/**
 * An interface to abstract the SystemClock.uptimeMillis() Android API in non Android artifacts.
 *
 * You can create a [Clock] from a lambda by calling [invoke].
 */
interface Clock {
  /**
   * On Android VMs, this should return android.os.SystemClock.uptimeMillis().
   */
  fun uptimeMillis(): Long

  companion object {
    /**
     * Utility function to create a [Clock] from the passed in [block] lambda
     * instead of using the anonymous `object : Clock` syntax.
     *
     * Usage:
     *
     * ```kotlin
     * val clock = Clock {
     *
     * }
     * ```
     */
    inline operator fun invoke(crossinline block: () -> Long): Clock =
      object : Clock {
        override fun uptimeMillis(): Long = block()
      }
  }
}
