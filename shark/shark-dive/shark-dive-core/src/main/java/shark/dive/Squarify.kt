package shark.dive

import kotlin.math.max
import kotlin.math.sqrt

/** The golden ratio, the aspect ratio the squarified layout aims for. */
private val PHI = (1 + sqrt(5.0)) / 2

/**
 * Lays [weights] out into [rect] as a squarified treemap: rectangles with an area proportional to
 * their weight, arranged in rows chosen to keep aspect ratios as close to square as possible.
 *
 * From "Squarified Treemaps" (Bruls, Huizing, van Wijk), following d3-hierarchy's implementation.
 *
 * [weights] must be sorted descending, which is what the algorithm assumes when it decides where to
 * break a row. Zero weights produce empty rectangles.
 *
 * All ratio arithmetic is in [Double] on purpose. In [Int] it overflows once weights exceed ~46 341,
 * because of the `sumValue * sumValue` term, which silently corrupts every row break decision at the
 * scale of real retained heap sizes.
 */
internal fun squarify(
  weights: LongArray,
  rect: TreemapRect
): List<TreemapRect> {
  val rects = arrayOfNulls<TreemapRect>(weights.size)

  var left = rect.left
  var top = rect.top
  val right = rect.right
  val bottom = rect.bottom

  // Remaining weight still to be placed, shrinking as each row is laid out. Doubles as the
  // denominator that converts a row's weight into its share of the remaining space.
  var remainingWeight = weights.sum().toDouble()

  var rowStart = 0
  val n = weights.size
  while (rowStart < n) {
    val dx = right - left
    val dy = bottom - top
    if (dx <= 0.0 || dy <= 0.0 || remainingWeight <= 0.0) {
      // No space or no weight left: everything remaining collapses to empty rectangles.
      for (i in rowStart until n) {
        rects[i] = TreemapRect(left, top, left, top)
      }
      break
    }

    // Grow a row one node at a time for as long as the worst aspect ratio in it keeps improving.
    var rowEnd = rowStart
    var rowWeight = weights[rowEnd].toDouble()
    rowEnd++
    var minWeight = rowWeight
    var maxWeight = rowWeight
    val alpha = max(dy / dx, dx / dy) / (remainingWeight * PHI)
    var beta = rowWeight * rowWeight * alpha
    var bestRatio = if (beta > 0.0 && minWeight > 0.0) {
      max(maxWeight / beta, beta / minWeight)
    } else {
      Double.MAX_VALUE
    }
    while (rowEnd < n) {
      val weight = weights[rowEnd].toDouble()
      rowWeight += weight
      if (weight < minWeight) minWeight = weight
      if (weight > maxWeight) maxWeight = weight
      beta = rowWeight * rowWeight * alpha
      val ratio = if (beta > 0.0 && minWeight > 0.0) {
        max(maxWeight / beta, beta / minWeight)
      } else {
        Double.MAX_VALUE
      }
      if (ratio > bestRatio) {
        rowWeight -= weight
        break
      }
      bestRatio = ratio
      rowEnd++
    }

    // Lay the row across the shorter side so that the row itself stays close to square, then
    // consume the band it occupies.
    val rowShare = rowWeight / remainingWeight
    if (dx < dy) {
      val rowBottom = top + dy * rowShare
      dice(weights, rowStart, rowEnd, rowWeight, left, top, right, rowBottom, rects)
      top = rowBottom
    } else {
      val rowRight = left + dx * rowShare
      slice(weights, rowStart, rowEnd, rowWeight, left, top, rowRight, bottom, rects)
      left = rowRight
    }

    remainingWeight -= rowWeight
    rowStart = rowEnd
  }

  @Suppress("UNCHECKED_CAST")
  return (rects as Array<TreemapRect>).asList()
}

/** Places a row's nodes side by side horizontally, each spanning the full height of the band. */
private fun dice(
  weights: LongArray,
  from: Int,
  until: Int,
  rowWeight: Double,
  left: Double,
  top: Double,
  right: Double,
  bottom: Double,
  rects: Array<TreemapRect?>
) {
  var x = left
  val scale = if (rowWeight > 0.0) (right - left) / rowWeight else 0.0
  for (i in from until until) {
    val nodeRight = x + weights[i] * scale
    rects[i] = TreemapRect(x, top, nodeRight, bottom)
    x = nodeRight
  }
}

/** Places a row's nodes stacked vertically, each spanning the full width of the band. */
private fun slice(
  weights: LongArray,
  from: Int,
  until: Int,
  rowWeight: Double,
  left: Double,
  top: Double,
  right: Double,
  bottom: Double,
  rects: Array<TreemapRect?>
) {
  var y = top
  val scale = if (rowWeight > 0.0) (bottom - top) / rowWeight else 0.0
  for (i in from until until) {
    val nodeBottom = y + weights[i] * scale
    rects[i] = TreemapRect(left, y, right, nodeBottom)
    y = nodeBottom
  }
}
