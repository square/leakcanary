package leakcanary

import kotlin.time.Duration

fun interface DelayedExecutor {
  fun executeWithDelay(delayUptime: Duration, runnable: Runnable)
}
