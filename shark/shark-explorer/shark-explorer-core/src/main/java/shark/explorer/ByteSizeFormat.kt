package shark.explorer

import java.util.Locale

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

private const val UNIT = 1024
private val UNITS = listOf("KB", "MB", "GB", "TB")
