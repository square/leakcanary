package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import shark.explorer.DrawnRootPath
import shark.explorer.HEAD_INDEX
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectSummary
import shark.explorer.RootPath
import shark.explorer.RootPathStep
import shark.explorer.RootPathWay
import shark.explorer.detours
import shark.explorer.drawnWith
import shark.explorer.stepsAfter
import shark.explorer.stepsBelow

/**
 * The chain of objects holding whatever the window is describing, from the whole heap dump down to it.
 *
 * Beside the map rather than in the details panel, because a chain is a column of objects with something to
 * say about each of them and the panel is already one: both of them in one pane means one is always
 * scrolled off, and pointing at a rectangle is exactly when the two are worth reading together.
 *
 * The objects that dominate the one at the end are ringed and labelled, which is what ties the chain back to
 * the treemap: those are the rectangles it is drawn inside of, and the rest are only on the way to it. Every
 * object of it is a click away from the map going there, so this is also the way back out — the ringed steps
 * are the nesting the map zoomed through, in the order it zoomed through them, and the whole heap dump at
 * the top of it is the way back to the first screen.
 *
 * **What the pointer is on is drawn onto the end of it**, lightly, rather than as a panel of its own: the
 * rectangle under the pointer is inside the one the window is describing, so the chain holding it is this
 * chain and a few more steps. Which makes moving the pointer around the map read as the chain growing and
 * shrinking, and leaves the reader the part of it they were already reading.
 *
 * Everything here is read on the heap dump's thread — see [shark.explorer.HeapDominatorTreemap.rootPathTo] —
 * so [rootPath] arrives a little after whatever was pointed at changed.
 */
@Composable
internal fun RootPathPanel(
  /** The cell clicked, which is what this pane is about however far the pointer has moved since. */
  selection: Selection?,
  /** Null until the walk up to the GC roots has come back. */
  rootPath: RootPath?,
  /** The cell under the pointer, whose chain is drawn onto the end of this one. */
  hoveredSelection: Selection?,
  hoveredRootPath: RootPath?,
  /** The node the map is rooted at, which is where a hovered chain starts when it isn't below this one. */
  rootNodeId: Long,
  /** The ways each stretch of the chain could run, by [shark.explorer.RootPathDetour.fromIndex]. */
  ways: Map<Int, List<RootPathWay>>,
  /** Which of them is drawn, for the stretches the reader has switched. */
  chosenWays: Map<Int, Int>,
  onChooseWay: (Int, Int) -> Unit,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  val summary = (selection as? Selection.Object)?.summary
  val isWholeHeapDump = summary?.objectId == HeapDominatorTreemap.ROOT_OBJECT_ID
  val chain = rootPath?.takeIf { summary != null && !isWholeHeapDump && it.steps.isNotEmpty() }
  val drawn = chain?.let { path ->
    path.drawnWith(path.detours()) { detour ->
      val chosen = chosenWays[detour.fromIndex] ?: 0
      // The chain's own way is the first of them, and it is already where it is.
      if (chosen == 0) null else ways[detour.fromIndex]?.getOrNull(chosen)
    }
  }
  // Only the steps the chain on screen doesn't already have, which is what makes this the same chain running
  // on rather than a second one. Null when the pointer is on a rectangle that isn't inside the object shown,
  // which a click on an object that dominates nothing leaves the map able to do.
  val target = drawn?.path?.steps?.lastOrNull()?.step?.objectId
  val hoveredChain = hoveredRootPath?.takeIf { (hoveredSelection as? Selection.Object) != null }
  val tail = hoveredChain?.let { hovered ->
    target?.let { hovered.stepsAfter(it) }?.takeIf { it.isNotEmpty() }
  }
  val cutTail = if (tail == null) {
    hoveredChain?.stepsBelow(rootNodeId)?.takeIf { it.isNotEmpty() }
  } else {
    null
  }
  val scrollState = rememberScrollState()
  // The bottom of the chain is what a reader is looking at, so that is what the pane is scrolled to: opening
  // an object runs the chain down to it, and the last few steps are the ones that say how it is held. Every
  // time the chain grows, which is also the pointer moving from rectangle to rectangle.
  val bottomObjectId = (tail ?: cutTail)?.lastOrNull()?.step?.objectId ?: target
  LaunchedEffect(bottomObjectId) {
    if (bottomObjectId != null) {
      snapshotFlow { scrollState.maxValue }.collect { scrollState.animateScrollTo(it) }
    }
  }
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.verticalScroll(scrollState).padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      // Where every chain starts, and the way back to the screen the window opens on.
      PathRootRow(
        nextStrength = drawn?.path?.steps?.firstOrNull()?.step?.strength
          ?: cutTail?.firstOrNull()?.step?.strength,
        onOpen = onOpen
      )
      if (drawn != null) {
        RootPathTrace(
          drawn = drawn,
          ways = ways,
          chosenWays = chosenWays,
          onChooseWay = onChooseWay,
          coloring = coloring,
          onOpen = onOpen
        )
      } else {
        noChainText(selection, summary, isWholeHeapDump, rootPath, hasTail = cutTail != null)?.let {
          Text(it, style = MaterialTheme.typography.bodySmall)
        }
      }
      if (tail != null) {
        HoveredTail(steps = tail, isCut = false, coloring = coloring)
      } else if (cutTail != null) {
        // Nothing above it on screen is what holds it, so the end of it is the object being described here.
        HoveredTail(steps = cutTail, isCut = true, coloring = coloring)
      }
    }
  }
}

/** What there is to say when there is no chain to draw, and null when the chain says it. */
private fun noChainText(
  selection: Selection?,
  summary: HeapObjectSummary?,
  isWholeHeapDump: Boolean,
  rootPath: RootPath?,
  hasTail: Boolean
): String? = when {
  // The whole heap dump is the row above: every chain starts below it rather than reaching it.
  isWholeHeapDump -> null
  // Whatever the pointer is on is being drawn, and that is more use than a line asking for a click.
  hasTail -> null
  selection == null -> NO_ROOT_PATH_YET
  // A pile of objects is held as many ways as it has objects in it, so there is no one chain.
  summary == null -> NO_ROOT_PATH_FOR_A_PILE
  rootPath == null -> SEARCHING_ROOT_PATH
  else -> NO_ROOT_PATH
}

/** The chain itself: the GC root at the top, the object the window is describing at the bottom. */
@Composable
private fun RootPathTrace(
  drawn: DrawnRootPath,
  ways: Map<Int, List<RootPathWay>>,
  chosenWays: Map<Int, Int>,
  onChooseWay: (Int, Int) -> Unit,
  coloring: CellColoring,
  onOpen: (Long) -> Unit
) {
  val steps = drawn.path.steps
  Column(Modifier.fillMaxWidth()) {
    PathHeadRow(
      label = drawn.path.headLabel(),
      reference = steps.first().step.reference,
      nextStrength = steps.first().step.strength,
      below = { WaysOfDetour(drawn, HEAD_INDEX, ways, chosenWays, onChooseWay) }
    )
    steps.forEachIndexed { depth, step ->
      val next = steps.getOrNull(depth + 1)
      PathStepRow(
        step = step.step,
        // How this step points at the next one, which is what the next step was reached through.
        reference = next?.step?.reference,
        nextStrength = next?.step?.strength,
        coloring = coloring,
        onOpen = onOpen,
        role = when {
          // The object the details panel is about, whatever the pointer has added below it.
          next == null -> PathRole.TARGET
          step.isDominator -> PathRole.DOMINATOR
          else -> PathRole.STEP
        },
        below = { WaysOfDetour(drawn, depth, ways, chosenWays, onChooseWay) }
      )
    }
  }
}

/**
 * The chain for the rectangle under the pointer, drawn onto the end of the chain for the object shown.
 *
 * Lightly: what the reader wants of a rectangle they are only pointing at is which objects hold it and how
 * much each of those is worth, and everything else on a full row of the chain is four more lines of a pane
 * that is already the height of the window.
 */
@Composable
private fun HoveredTail(
  steps: List<RootPathStep>,
  /** Whether it runs on from the chain above or starts somewhere else, which the dots say. */
  isCut: Boolean,
  coloring: CellColoring
) {
  Column(Modifier.fillMaxWidth()) {
    if (isCut) {
      // Not below the object shown, so the chain above isn't the way to this one: the dots are that said in
      // the gutter, and the map itself is the rest of the answer.
      PathCutRow(nextStrength = steps.first().step.strength)
    }
    steps.forEachIndexed { depth, step ->
      val next = steps.getOrNull(depth + 1)
      PathStepRow(
        step = step.step,
        reference = next?.step?.reference,
        nextStrength = next?.step?.strength,
        coloring = coloring,
        // Nothing to click: the pointer is on the map, and it leaving the map is what takes this away.
        onOpen = {},
        role = when {
          // The end of a cut tail is the only object being described here, since the chain above it isn't
          // the way to it; the end of one that runs on is described by the card at the pointer instead.
          next == null && isCut -> PathRole.TARGET
          step.isDominator -> PathRole.DOMINATOR
          else -> PathRole.STEP
        },
        detail = PathDetail.BRIEF
      )
    }
  }
}

/**
 * How else the stretch of the chain below one step could have run, and which of those ways is drawn.
 *
 * Only where there is a choice to make. A run of steps between two objects that both dominate the object at
 * the end is a run the chain didn't have to take — if it had, those steps would dominate it too — so this is
 * where "held how else?" has an answer, and the arrows are how the reader reads the others.
 */
@Composable
private fun WaysOfDetour(
  drawn: DrawnRootPath,
  row: Int,
  ways: Map<Int, List<RootPathWay>>,
  chosenWays: Map<Int, Int>,
  onChooseWay: (Int, Int) -> Unit
) {
  val detour = drawn.detourByRow[row] ?: return
  val found = ways[detour.fromIndex] ?: return
  if (found.size < 2) {
    return
  }
  val chosen = chosenWays[detour.fromIndex] ?: 0
  Row(
    Modifier.padding(top = 2.dp)
      .background(WAYS_BACKGROUND, RoundedCornerShape(4.dp))
      .padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      PREVIOUS_WAY,
      Modifier.clickableRow { onChooseWay(detour.fromIndex, (chosen - 1 + found.size) % found.size) },
      style = MaterialTheme.typography.bodySmall
    )
    Hint(WAYS_HINT) {
      Text(
        "${chosen + 1} of ${found.size} $WAYS_FROM_HERE",
        style = MaterialTheme.typography.labelSmall,
        color = MUTED_TEXT
      )
    }
    Text(
      NEXT_WAY,
      Modifier.clickableRow { onChooseWay(detour.fromIndex, (chosen + 1) % found.size) },
      style = MaterialTheme.typography.bodySmall
    )
  }
}

/**
 * Which GC root the chain starts at, and how many steps below it were left out.
 *
 * When steps were left out, the reference drawn under this row belongs to the last of the objects left out
 * rather than to the root, and the class it names is how the reader can tell.
 */
private fun RootPath.headLabel(): String = if (hiddenStepCount == 0) {
  gcRootLabel.orEmpty()
} else {
  "${gcRootLabel.orEmpty()}, then $ELLIPSIS $hiddenStepCount steps"
}

/** Shown until a rectangle has been clicked, which is what this pane draws a chain for. */
internal const val NO_ROOT_PATH_YET = "Click a rectangle to see what holds it."

internal const val NO_ROOT_PATH_FOR_A_PILE =
  "Not one object, so there is no one chain holding it. Click it to reach the objects it stands for."

/** Shown while the walk up to the GC roots is still running. */
internal const val SEARCHING_ROOT_PATH = "Working out what holds it…"

internal const val NO_ROOT_PATH = "No chain from a GC root down to this object was found."

/** What the arrows either side of a stretch of the chain do, which is worth spelling out once. */
internal const val WAYS_HINT =
  "The steps between two objects that both hold this one are a stretch the chain didn't have to take: if " +
    "it had, those steps would hold it too and be marked as dominators. So there are other ways from the " +
    "object above down to the one below, and these arrows walk through them. They share no object in " +
    "between, which graph theory calls independent, and they are found greedily, so there can be more of " +
    "them than are counted here."

/** What the count between the arrows counts, after which of them is drawn: `2 of 3 ways from here`. */
internal const val WAYS_FROM_HERE = "ways from here"

internal const val PREVIOUS_WAY = "◂"
internal const val NEXT_WAY = "▸"

internal const val ELLIPSIS = "…"

/** As wide as a class name plus what a step says about the object, and no wider: the map needs the room. */
internal val ROOT_PATH_WIDTH = 300.dp

/** What the arrows for switching a stretch of the chain sit on, so they don't read as one of its objects. */
private val WAYS_BACKGROUND = Color(0x14000000)
