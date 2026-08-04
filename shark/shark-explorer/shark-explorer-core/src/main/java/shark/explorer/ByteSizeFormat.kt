package shark.explorer

import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10

/**
 * Formats [byteCount] for display, e.g. `1.4 MB`.
 *
 * Powers of 1024, named the way Android tooling names them rather than as KiB / MiB. Always
 * [Locale.US], so that a decimal separator doesn't depend on where the app runs.
 */
fun formatByteSize(byteCount: Long): String {
  if (byteCount < UNIT) {
    return "$byteCount B"
  }
  var scaled = byteCount.toDouble()
  for (unit in UNITS) {
    scaled /= UNIT
    if (scaled < UNIT) {
      // One decimal below 10 and none above: "9.6 MB" then "42 MB", which keeps the width stable
      // enough to sit next to a treemap rectangle.
      val format = if (scaled < 10) "%.1f %s" else "%.0f %s"
      return String.format(Locale.US, format, scaled, unit)
    }
  }
  return String.format(Locale.US, "%.0f %s", scaled, UNITS.last())
}

/**
 * Formats [byteCount] with what it is of [totalByteCount], e.g. `1.4 MB (0.0012% total)`.
 *
 * The share is what says whether a size matters: the same megabyte is a tenth of a small heap dump
 * and a rounding error of a large one. It goes right after the size it qualifies rather than at the
 * end of the line, where it would read as a share of whatever else the line says.
 */
fun formatByteSizeOfTotal(
  byteCount: Long,
  totalByteCount: Long
): String = "${formatByteSize(byteCount)} (${formatPercentOfTotal(byteCount, totalByteCount)} total)"

/**
 * [byteCount] as a percentage of [totalByteCount], e.g. `1%` or `0.0012%`, and `<0.0001%` below what
 * that can show.
 *
 * Two significant digits rather than whole percents, because what a single object holds is usually a
 * thousandth of a percent of the heap dump, and rounding would report all but the largest as holding
 * nothing at all. Always [Locale.US], for the same reason [formatByteSize] is.
 */
fun formatPercentOfTotal(
  byteCount: Long,
  totalByteCount: Long
): String {
  if (totalByteCount <= 0L) {
    // Nothing to be a share of. Never divide by zero rather than something a reader gets to see: an
    // empty heap dump is no heap dump.
    return "0%"
  }
  val percent = byteCount * PERCENT_SCALE / totalByteCount
  if (percent <= 0.0) {
    return "0%"
  }
  if (percent < MIN_PERCENT) {
    // Written out rather than interpolated, which would render it as 1.0E-4.
    return "<${percentWithDecimals(MIN_PERCENT, MAX_DECIMALS)}%"
  }
  // As many decimals as two significant digits take: one for 1.4, four for 0.0012, none past 10.
  val decimals = (1 - floor(log10(percent)).toInt()).coerceIn(0, MAX_DECIMALS)
  val formatted = percentWithDecimals(percent, decimals)
  // The zeros rounding left behind read as precision that isn't there: 1.0 becomes 1, 1.4 stays.
  // Only past the point, or trimming would turn 100 into 1.
  return if ('.' in formatted) {
    "${formatted.trimEnd('0').trimEnd('.')}%"
  } else {
    "$formatted%"
  }
}

private fun percentWithDecimals(
  percent: Double,
  decimals: Int
): String = String.format(Locale.US, "%.${decimals}f", percent)

/**
 * Formats [objectCount] for display, e.g. `1 object` or `807,231 objects`.
 *
 * Grouped in threes and never abbreviated: a count of objects sits next to a byte count, and rounding
 * both would leave the eye nothing exact to hold on to. Always [Locale.US], for the same reason
 * [formatByteSize] is.
 */
fun formatObjectCount(objectCount: Int): String {
  val count = String.format(Locale.US, "%,d", objectCount)
  return if (objectCount == 1) "$count object" else "$count objects"
}

private const val UNIT = 1024
private val UNITS = listOf("KB", "MB", "GB", "TB")

private const val PERCENT_SCALE = 100.0

/** Below this a percentage is reported as being under it. See [formatPercentOfTotal]. */
private const val MIN_PERCENT = 0.0001

/** Enough decimals for [MIN_PERCENT] to be written out. */
private const val MAX_DECIMALS = 4
