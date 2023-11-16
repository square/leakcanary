package leakcanary

import kotlin.time.Duration

/**
 * An interface to abstract the clock to get the system uptime.
 */
fun interface UptimeClock {
  /**
   * On JVMs this should return [System.nanoTime] as a [Duration].
   *
   * On Android VMs, this should return either [System.nanoTime] on Android 11 (when the method
   * was annotated with @CriticalNative) or [android.os.SystemClock.uptimeMillis()] before
   * Android 11.
   */
  fun uptime(): Duration
}
