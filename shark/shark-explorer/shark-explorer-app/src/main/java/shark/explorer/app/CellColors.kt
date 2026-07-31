package shark.explorer.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.lerp
import shark.explorer.CellContent
import shark.explorer.CellSubject
import shark.explorer.ObjectGroupKind
import shark.explorer.PresentedCell
import shark.explorer.ReachabilityStrength
import shark.explorer.ReachabilityStrength.CACHE
import shark.explorer.ReachabilityStrength.FINALIZER
import shark.explorer.ReachabilityStrength.LOCAL
import shark.explorer.ReachabilityStrength.PHANTOM
import shark.explorer.ReachabilityStrength.SOFT
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.ReachabilityStrength.THREAD_LOCAL
import shark.explorer.ReachabilityStrength.UNREACHABLE
import shark.explorer.ReachabilityStrength.WEAK

/**
 * How the rectangles are coloured. Pick one above the view.
 *
 * Whichever is picked, an object that isn't strongly reachable keeps its own vivid hue, because
 * spotting one nested inside a strongly reachable block is the whole point of colouring by strength at
 * all. What a scheme decides is how the rest of the heap — nearly all of it — is coloured.
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
 * How a view is coloured: which scheme, and which strengths are shown in colour.
 *
 * Unchecking a strength greys out everything held that firmly instead of hiding it — the tree is the
 * whole heap dump either way. Greying the strong heap is what makes the little there is of everything
 * else jump out; greying everything else is what stops it from doing so.
 */
internal data class CellColoring(
  val scheme: CellColorScheme,
  val coloredStrengths: Set<ReachabilityStrength>
) {
  companion object {
    val DEFAULT = CellColoring(
      scheme = CellColorScheme.DAISY,
      coloredStrengths = ReachabilityStrength.values().toSet()
    )
  }
}

/**
 * The colours of one laid out view. Works off [PresentedCell] alone, so a treemap and a radial view are
 * coloured by the same code.
 *
 * Built per presentation because [CellColorScheme.DAISY] needs a hue per top level block that everything
 * below it inherits, which takes a pass over the cells: they come parent before child, so a cell's parent
 * has always been given its hue by the time the cell is reached.
 */
internal class CellColors private constructor(
  private val coloring: CellColoring,
  private val hueIndexByObjectId: Map<Long, Int>
) {

  val label: Color get() = LABEL

  /** Dark enough on a pile of objects for the dashes of [outlineOf] to read as dashes. */
  fun borderOf(presented: PresentedCell<*>): Color = when (presented.content) {
    is CellContent.Object -> if (coloring.scheme == DAISY_SCHEME) DAISY_BORDER else BORDER
    else -> PILE_BORDER
  }

  fun colorOf(presented: PresentedCell<*>): Color {
    val depth = presented.cell.depth
    val strength = presented.strength
    if (strength !in coloring.coloredStrengths) {
      return mutedColor(depth)
    }
    return when (val content = presented.content) {
      // A pile of objects is neither an object's hue nor the grey of a strength switched off, because it
      // is neither of those things.
      is CellContent.Leftover -> pileColor(strength, depth)
      is CellContent.ObjectGroup -> if (content.kind == ObjectGroupKind.CLASS) {
        pileColor(strength, depth)
      } else {
        objectColor(strength, depth, hueIndexOf(presented))
      }
      is CellContent.Object -> objectColor(strength, depth, hueIndexOf(presented))
    }
  }

  private fun hueIndexOf(presented: PresentedCell<*>): Int {
    val objectId = when (val subject = presented.cell.subject) {
      is CellSubject.Node -> subject.node
      // Inherits its object's hue: it is that object, so a different one would read as a child.
      is CellSubject.Own -> subject.node
      is CellSubject.Group -> null
    }
    return objectId?.let { hueIndexByObjectId[it] } ?: 0
  }

  private fun objectColor(
    strength: ReachabilityStrength,
    depth: Int,
    hueIndex: Int
  ): Color = when (strength) {
    STRONG -> strongColor(coloring.scheme, depth, hueIndex)
    // Shaded by depth like the strong heap, unlike the other strengths: there can be megabytes of
    // uncollected garbage, and one flat colour over all of it would hide its shape.
    UNREACHABLE -> shaded(UNREACHABLE.hue, UNREACHABLE_SATURATION, depth)
    else -> strengthColor(strength)
  }

  /**
   * The colour of a cell standing for many objects: a cool slate for the strongly reachable ones, and the
   * washed out shade of their strength for the rest, so that a pile of garbage still reads as garbage.
   */
  fun pileColor(
    strength: ReachabilityStrength,
    depth: Int
  ): Color = if (strength == STRONG) {
    shaded(PILE_HUE, PILE_SATURATION, depth, PILE_SATURATION_STEP)
  } else {
    shaded(strength.hue, PILE_SATURATION, depth, PILE_SATURATION_STEP)
  }

  /** What a strength switched off in the top bar looks like: grey, and nothing else is. */
  fun mutedColor(depth: Int): Color =
    Color.hsv(hue = 0f, saturation = 0f, value = MAX_VALUE - (depth % SHADE_STEPS) * VALUE_STEP)

  companion object {
    private val DAISY_SCHEME = CellColorScheme.DAISY

    fun of(
      coloring: CellColoring,
      cells: List<PresentedCell<*>>
    ): CellColors {
      val hueIndexByObjectId = if (coloring.scheme != DAISY_SCHEME) {
        emptyMap()
      } else {
        val hueIndexByObjectId = mutableMapOf<Long, Int>()
        cells.forEach { presented ->
          val subject = presented.cell.subject
          if (subject is CellSubject.Node) {
            hueIndexByObjectId[subject.node] = when {
              // Down to the children of the two halves of the heap dump: colouring by the halves
              // themselves would give the whole view one or two hues.
              presented.cell.depth <= TOP_LEVEL_DEPTH -> subject.siblingIndex
              // Inherits, so that everything one block contains shares its hue.
              else -> hueIndexByObjectId[subject.parent] ?: subject.siblingIndex
            }
          }
        }
        hueIndexByObjectId
      }
      return CellColors(coloring, hueIndexByObjectId)
    }
  }
}

/** The swatch shown next to a strength's checkbox, and in the details panel. */
internal fun legendColor(
  coloring: CellColoring,
  strength: ReachabilityStrength
): Color = when {
  strength !in coloring.coloredStrengths -> Color.hsv(hue = 0f, saturation = 0f, value = MUTED_SWATCH)
  strength == STRONG -> strongColor(coloring.scheme, depth = 1, hueIndex = 0)
  strength == UNREACHABLE -> shaded(UNREACHABLE.hue, UNREACHABLE_SATURATION, depth = 1)
  else -> strengthColor(strength)
}

/** Vivid and the same in every scheme: there is never much of it, and it has to be impossible to miss. */
private fun strengthColor(strength: ReachabilityStrength): Color =
  Color.hsv(strength.hue, saturation = STRENGTH_SATURATION, value = STRENGTH_VALUE)

private fun strongColor(
  scheme: CellColorScheme,
  depth: Int,
  hueIndex: Int
): Color = when (scheme) {
  // Lighter the deeper it sits, so nesting reads without a border to look for. Interpolated between
  // named bounds rather than stepped per level, because a step per level walks out of the range HSV
  // accepts: adding 0.018 to a 0.90 value crashed at depth 7, on the first heap dump nested that deep.
  CellColorScheme.DAISY -> {
    val deepest = depth.coerceAtMost(DAISY_DEEPEST_SHADE).toFloat() / DAISY_DEEPEST_SHADE
    Color.hsv(
      hue = DAISY_HUES[hueIndex % DAISY_HUES.size],
      saturation = lerp(DAISY_MAX_SATURATION, DAISY_MIN_SATURATION, deepest),
      value = lerp(DAISY_MIN_VALUE, DAISY_MAX_VALUE, deepest)
    )
  }
  CellColorScheme.REACHABILITY -> shaded(STRONG.hue, SHADED_SATURATION, depth)
  CellColorScheme.SLATE -> shaded(SLATE_HUE, SLATE_SATURATION, depth)
}

/** Cycles saturation and brightness with depth, which is unbounded, so the shades have to repeat. */
private fun shaded(
  hue: Float,
  minSaturation: Float,
  depth: Int,
  saturationStep: Float = SATURATION_STEP
): Color {
  val step = depth % SHADE_STEPS
  return Color.hsv(
    hue = hue,
    saturation = minSaturation + step * saturationStep,
    value = MAX_VALUE - step * VALUE_STEP
  )
}

/** How the UI names a strength, e.g. next to its checkbox. */
internal val ReachabilityStrength.displayName: String
  get() = when (this) {
    STRONG -> "Strong"
    CACHE -> "Cache"
    THREAD_LOCAL -> "Thread local"
    LOCAL -> "Local"
    SOFT -> "Soft"
    WEAK -> "Weak"
    FINALIZER -> "Finalizer"
    PHANTOM -> "Phantom"
    UNREACHABLE -> "Unreachable"
  }

/** How the details panel says an object is reachable. */
internal val ReachabilityStrength.reachabilityText: String
  get() = when (this) {
    STRONG -> "Strongly reachable"
    CACHE -> "Held only by a cache that evicts"
    THREAD_LOCAL -> "Held only by a thread's own storage"
    LOCAL -> "Held only by a running method"
    SOFT -> "Softly reachable"
    WEAK -> "Weakly reachable"
    FINALIZER -> "Reachable only from the finalizer queue"
    PHANTOM -> "Phantom reachable"
    UNREACHABLE -> "Unreachable: uncollected garbage"
  }

private val ReachabilityStrength.hue: Float
  get() = when (this) {
    STRONG -> 210f
    CACHE -> 130f
    THREAD_LOCAL -> 100f
    LOCAL -> 320f
    SOFT -> 35f
    WEAK -> 285f
    FINALIZER -> 0f
    PHANTOM -> 160f
    UNREACHABLE -> 255f
  }

/**
 * Hues around the wheel, ordered so that consecutive top level blocks land far apart. Kept clear of
 * the muddy yellow greens, which read as a rendering fault rather than as a colour.
 */
private val DAISY_HUES = floatArrayOf(
  4f, 200f, 42f, 262f, 168f, 330f, 24f, 232f, 140f, 292f, 60f, 190f
)
private const val DAISY_MAX_SATURATION = 0.62f
private const val DAISY_MIN_SATURATION = 0.18f
private const val DAISY_MIN_VALUE = 0.90f
private const val DAISY_MAX_VALUE = 0.99f

/** The depth the ramp bottoms out at. Deeper than this and nesting has to read from the borders. */
private const val DAISY_DEEPEST_SHADE = 8
private val DAISY_BORDER = Color(0x66FFFFFF)

/**
 * The depth of the two halves of the heap dump: the root is 0, "All GC roots" and "Unreachable" are 1, and
 * their children — the blocks worth telling apart by hue — are 2.
 */
private const val TOP_LEVEL_DEPTH = 2

private const val SLATE_HUE = 212f
private const val SLATE_SATURATION = 0.07f

/** The cool slate a pile of strongly reachable objects gets, which no object is coloured. */
private const val PILE_HUE = 196f
private const val PILE_SATURATION = 0.30f
private const val PILE_SATURATION_STEP = 0.05f
private val PILE_BORDER = Color(0xCC37474F)

/** Vivid, so that the little there is of it is impossible to miss among pastels. */
private const val STRENGTH_SATURATION = 0.85f
private const val STRENGTH_VALUE = 0.95f

/** There can be a lot of garbage, so it's shaded rather than flat, and not quite as loud. */
private const val UNREACHABLE_SATURATION = 0.45f

private const val SHADED_SATURATION = 0.20f
private const val SHADE_STEPS = 5
private const val SATURATION_STEP = 0.11f
private const val MAX_VALUE = 0.99f
private const val VALUE_STEP = 0.06f

/** Dark enough to read as switched off next to an unchecked box. */
private const val MUTED_SWATCH = 0.72f

private val BORDER = Color(0x33000000)
private val LABEL = Color(0xFF1B1B1B)

/** The outline of the selected rectangle. The same in every scheme: it isn't part of the picture. */
internal val SELECTION_COLOR = Color(0xFF0B57D0)

/**
 * And of the one under the pointer: the same hue washed out, because the two mean the same thing about a
 * rectangle — the panels are describing it — and only one of them survives the pointer moving on.
 */
internal val HOVER_COLOR = Color(0x990B57D0)
