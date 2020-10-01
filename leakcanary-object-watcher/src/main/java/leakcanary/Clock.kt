package leakcanary

/**
 * An interface to abstract the SystemClock.uptimeMillis() Android API in non Android artifacts.
 *
 * This is a functional interface with which you can create a [Clock] from a lambda.
 */
fun interface Clock {
  /**
   * On Android VMs, this should return android.os.SystemClock.uptimeMillis().
   */
  fun uptimeMillis(): Long

  companion object {

    @Deprecated("Leverage Kotlin SAM lambda expression")
    inline operator fun invoke(crossinline block: () -> Long): Clock =
      object : Clock {
        override fun uptimeMillis(): Long = block()
      }
  }
}
