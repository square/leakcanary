package shark.explorer.app

import androidx.compose.ui.graphics.Color
import shark.explorer.PresentedCell
import shark.explorer.ReachabilityStrength
import shark.explorer.ReachabilityStrength.FINALIZER
import shark.explorer.ReachabilityStrength.PHANTOM
import shark.explorer.ReachabilityStrength.SOFT
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.WEAK
import shark.explorer.TreemapCell
import shark.explorer.TreemapPresentation

/**
 * How the rectangles are coloured. Pick one in the top bar.
 *
 * Whichever is picked, an object that isn't strongly reachable keeps its own vivid hue, because
 * spotting one nested inside a strongly reachable block is the whole point of following a weaker
 * strength. What a scheme decides is how the rest of the heap — nearly all of it — is coloured.
 */
internal enum class CellColorScheme(val displayName: String) {

  /**
   * A hue per top level block, inherited by everything nested in it and lightening with depth, the way
   * DaisyDisk colours a disk. Neighbouring blocks differ, and a block reads as one thing with its
   * contents rather than as a pile of unrelated rectangles.
   */
  DAISY("Daisy"),

  /**
   * One hue per reachability strength, shaded by depth. Says the least about structure and the most
   * about what the garbage collector thinks.
   */
  REACHABILITY("Reachability"),

  /** Blue greys only, for when the colours get in the way of the shapes. */
  SLATE("Slate")
}

/**
 * The colours of one laid out treemap, in one scheme.
 *
 * Built per presentation because [DAISY] needs a hue per top level block that everything below it
 * inherits, which takes a pass over the cells: they come parent before child, so a cell's parent has
 * always been given its hue by the time the cell is reached.
 */
internal class CellColors private constructor(
  private val scheme: CellColorScheme,
  private val hueIndexByObjectId: Map<Long, Int>
) {

  val border: Color get() = if (scheme == DAISY_SCHEME) DAISY_BORDER else BORDER
  val label: Color get() = LABEL

  fun colorOf(cell: PresentedCell): Color {
    val strength = cell.strength ?: return groupColor(cell.cell.depth)
    if (strength != STRONG) {
      return strengthColor(strength)
    }
    val hueIndex = (cell.cell as? TreemapCell.Node)?.let { hueIndexByObjectId[it.node] } ?: 0
    return strongColor(scheme, cell.cell.depth, hueIndex)
  }

  /** The colour of the rectangle standing for the siblings that didn't fit: grey, so it reads as "not an object". */
  fun groupColor(depth: Int): Color =
    Color.hsv(hue = 0f, saturation = 0f, value = MAX_VALUE - (depth % SHADE_STEPS) * VALUE_STEP)

  companion object {
    private val DAISY_SCHEME = CellColorScheme.DAISY

    fun of(
      scheme: CellColorScheme,
      presentation: TreemapPresentation
    ): CellColors {
      val hueIndexByObjectId = if (scheme != DAISY_SCHEME) {
        emptyMap()
      } else {
        val hueIndexByObjectId = mutableMapOf<Long, Int>()
        presentation.cells.forEach { presented ->
          val cell = presented.cell
          if (cell is TreemapCell.Node) {
            hueIndexByObjectId[cell.node] = when {
              cell.depth <= 1 -> cell.siblingIndex
              // Inherits, so that everything one top level block contains shares its hue.
              else -> hueIndexByObjectId[cell.parent] ?: cell.siblingIndex
            }
          }
        }
        hueIndexByObjectId
      }
      return CellColors(scheme, hueIndexByObjectId)
    }
  }
}

/** The swatch shown next to a strength's checkbox, and in the details panel: what a top level block of
 * that strength would be coloured. */
internal fun legendColor(
  scheme: CellColorScheme,
  strength: ReachabilityStrength
): Color = if (strength == STRONG) {
  strongColor(scheme, depth = 1, hueIndex = 0)
} else {
  strengthColor(strength)
}

/** Vivid and the same in every scheme: there is never much of it, and it has to be impossible to miss. */
private fun strengthColor(strength: ReachabilityStrength): Color =
  Color.hsv(strength.hue, saturation = STRENGTH_SATURATION, value = STRENGTH_VALUE)

private fun strongColor(
  scheme: CellColorScheme,
  depth: Int,
  hueIndex: Int
): Color = when (scheme) {
  // Lighter the deeper it sits, so nesting reads without a border to look for.
  CellColorScheme.DAISY -> depth.coerceAtMost(DAISY_MAX_STEP).let { step ->
    Color.hsv(
      hue = DAISY_HUES[hueIndex % DAISY_HUES.size],
      saturation = DAISY_SATURATION - step * DAISY_SATURATION_STEP,
      value = DAISY_VALUE + step * DAISY_VALUE_STEP
    )
  }
  CellColorScheme.REACHABILITY -> shaded(STRONG.hue, SHADED_SATURATION, depth)
  CellColorScheme.SLATE -> shaded(SLATE_HUE, SLATE_SATURATION, depth)
}

/** Cycles saturation and brightness with depth, which is unbounded, so the shades have to repeat. */
private fun shaded(
  hue: Float,
  minSaturation: Float,
  depth: Int
): Color {
  val step = depth % SHADE_STEPS
  return Color.hsv(
    hue = hue,
    saturation = minSaturation + step * SATURATION_STEP,
    value = MAX_VALUE - step * VALUE_STEP
  )
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

private val ReachabilityStrength.hue: Float
  get() = when (this) {
    STRONG -> 210f
    SOFT -> 35f
    WEAK -> 285f
    FINALIZER -> 0f
    PHANTOM -> 160f
  }

/**
 * Hues around the wheel, ordered so that consecutive top level blocks land far apart. Kept clear of
 * the muddy yellow greens, which read as a rendering fault rather than as a colour.
 */
private val DAISY_HUES = floatArrayOf(
  4f, 200f, 42f, 262f, 168f, 330f, 24f, 232f, 140f, 292f, 60f, 190f
)
private const val DAISY_SATURATION = 0.62f
private const val DAISY_SATURATION_STEP = 0.055f
private const val DAISY_VALUE = 0.90f
private const val DAISY_VALUE_STEP = 0.018f
private const val DAISY_MAX_STEP = 8
private val DAISY_BORDER = Color(0x66FFFFFF)

private const val SLATE_HUE = 212f
private const val SLATE_SATURATION = 0.07f

/** Vivid, so that the little there is of it is impossible to miss among pastels. */
private const val STRENGTH_SATURATION = 0.85f
private const val STRENGTH_VALUE = 0.95f

private const val SHADED_SATURATION = 0.20f
private const val SHADE_STEPS = 5
private const val SATURATION_STEP = 0.11f
private const val MAX_VALUE = 0.99f
private const val VALUE_STEP = 0.06f

private val BORDER = Color(0x33000000)
private val LABEL = Color(0xFF1B1B1B)

/** The outline of the selected rectangle. The same in every scheme: it isn't part of the picture. */
internal val SELECTION_COLOR = Color(0xFF0B57D0)
