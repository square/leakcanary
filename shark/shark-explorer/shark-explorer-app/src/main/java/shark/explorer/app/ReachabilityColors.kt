package shark.explorer.app

import androidx.compose.ui.graphics.Color
import shark.explorer.ReachabilityStrength
import shark.explorer.ReachabilityStrength.FINALIZER
import shark.explorer.ReachabilityStrength.PHANTOM
import shark.explorer.ReachabilityStrength.SOFT
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.WEAK

/**
 * The colour of a rectangle: its hue says how strongly the object is reachable, and its shade says how
 * deeply it's nested.
 *
 * Hue carries the strength rather than the depth, because a weakly reachable object nested inside a
 * strongly reachable one is the thing worth spotting, and hue is what the eye picks out of a treemap.
 * Depth then varies saturation and brightness on a five step cycle: the layout puts no bound on depth,
 * so the shades have to repeat, and repeating is fine as long as neighbours differ.
 */
internal fun cellColor(
  strength: ReachabilityStrength,
  depth: Int
): Color {
  val step = depth % SHADE_STEPS
  return Color.hsv(
    hue = strength.hue,
    saturation = MIN_SATURATION + step * SATURATION_STEP,
    value = MAX_VALUE - step * VALUE_STEP
  )
}

/** The colour of the rectangle standing for the siblings that didn't fit: grey, so it reads as "not an object". */
internal fun groupCellColor(depth: Int): Color {
  val step = depth % SHADE_STEPS
  return Color.hsv(hue = 0f, saturation = 0f, value = MAX_VALUE - step * VALUE_STEP)
}

/** The colour to show in the legend and next to a checkbox, at the shade a top level rectangle gets. */
internal fun legendColor(strength: ReachabilityStrength): Color = cellColor(strength, depth = 1)

private val ReachabilityStrength.hue: Float
  get() = when (this) {
    STRONG -> 210f
    SOFT -> 35f
    WEAK -> 285f
    FINALIZER -> 0f
    PHANTOM -> 160f
  }

/** How the UI names a strength, e.g. next to its checkbox. */
internal val ReachabilityStrength.displayName: String
  get() = when (this) {
    STRONG -> "Strong"
    SOFT -> "Soft"
    WEAK -> "Weak"
    FINALIZER -> "Finalizer"
    PHANTOM -> "Phantom"
  }

/** How the details panel says an object is reachable. */
internal val ReachabilityStrength.reachabilityText: String
  get() = when (this) {
    STRONG -> "Strongly reachable"
    SOFT -> "Softly reachable"
    WEAK -> "Weakly reachable"
    FINALIZER -> "Reachable only from the finalizer queue"
    PHANTOM -> "Phantom reachable"
  }

private const val SHADE_STEPS = 5
private const val MIN_SATURATION = 0.20f
private const val SATURATION_STEP = 0.11f
private const val MAX_VALUE = 0.99f
private const val VALUE_STEP = 0.06f
