package leakcanary.internal.utils

import android.os.SystemClock

/**
 * Executes the given [block] and returns elapsed time in milliseconds using [SystemClock.uptimeMillis]
 */
internal inline fun measureDurationMillis(block: () -> Unit): Long {
    val start = SystemClock.uptimeMillis()
    block()
    return SystemClock.uptimeMillis() - start
}
