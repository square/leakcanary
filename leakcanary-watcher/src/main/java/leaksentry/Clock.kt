package leaksentry

interface Clock {
  /** See Android SystemClock.uptimeMillis().  */
  fun uptimeMillis(): Long
}
