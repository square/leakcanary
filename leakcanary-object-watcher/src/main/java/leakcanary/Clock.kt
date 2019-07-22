package leakcanary

/**
 * An interface to abstract the SystemClock.uptimeMillis() Android API in non Android artifacts.
 */
interface Clock {
  /**
   * On Android VMs, this should return android.os.SystemClock.uptimeMillis().
   */
  fun uptimeMillis(): Long
}
