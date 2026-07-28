package shark.explorer

/**
 * An axis aligned rectangle in layout space, with the origin at the top left.
 *
 * Deliberately not Compose's `Rect`: this module stays free of any Compose dependency so that its
 * layout logic can be unit tested without a UI harness and reused from Android.
 */
data class TreemapRect(
  val left: Double,
  val top: Double,
  val right: Double,
  val bottom: Double
) {
  val width: Double get() = right - left
  val height: Double get() = bottom - top
  val area: Double get() = width * height

  operator fun contains(point: TreemapPoint): Boolean =
    point.x >= left && point.x < right && point.y >= top && point.y < bottom

  /** Shrinks this rectangle by [top], [left], [right] and [bottom], never past its own center. */
  fun inset(
    left: Double = 0.0,
    top: Double = 0.0,
    right: Double = 0.0,
    bottom: Double = 0.0
  ): TreemapRect {
    val newLeft = this.left + left
    val newRight = this.right - right
    val newTop = this.top + top
    val newBottom = this.bottom - bottom
    // Collapse to the midpoint rather than inverting when the insets exceed the size.
    val (clampedLeft, clampedRight) = if (newRight < newLeft) {
      val mid = (newLeft + newRight) / 2
      mid to mid
    } else {
      newLeft to newRight
    }
    val (clampedTop, clampedBottom) = if (newBottom < newTop) {
      val mid = (newTop + newBottom) / 2
      mid to mid
    } else {
      newTop to newBottom
    }
    return TreemapRect(clampedLeft, clampedTop, clampedRight, clampedBottom)
  }
}

/** A point in the same space as [TreemapRect]. */
data class TreemapPoint(
  val x: Double,
  val y: Double
)
