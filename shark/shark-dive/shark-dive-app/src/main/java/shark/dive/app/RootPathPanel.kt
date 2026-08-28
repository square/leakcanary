package shark.dive.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import shark.dive.DrawnRootPath
import shark.dive.HEAD_INDEX
import shark.dive.HeapDominatorTreemap
import shark.dive.HeapObjectSummary
import shark.dive.LeakStatus
import shark.dive.PathReference
import shark.dive.RootPath
import shark.dive.RootPathStep
import shark.dive.RootPathWay
import shark.dive.Topic
import shark.dive.detours
import shark.dive.drawnWith
import shark.dive.faultyReference
import shark.dive.leakLabel
import shark.dive.stepsAfter
import shark.dive.stepsBelow

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
 * Everything here is read on the heap dump's thread — see [shark.dive.HeapDominatorTreemap.rootPathTo] —
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
  /** What a retained size here is a share of. See [shark.dive.HeapSizes.stronglyReachableByteCount]. */
  stronglyReachableByteCount: Long,
  /** The ways each stretch of the chain could run, by [shark.dive.RootPathDetour.fromIndex]. */
  ways: Map<Int, List<RootPathWay>>,
  /** Which of them is drawn, for the stretches the reader has switched. */
  chosenWays: Map<Int, Int>,
  onChooseWay: (Int, Int) -> Unit,
  onOpen: (Long, OpenIn) -> Unit,
  /** Puts a link to a step's object on the clipboard, beside opening it. See [OpenTarget]. */
  onCopyLink: (Long) -> Unit,
  /** Where the `?` beside what the chain says about itself goes. See [Explain]. */
  onExplain: (Topic) -> Unit,
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
  val listState = rememberLazyListState()
  // The bottom of the chain is what a reader is looking at, so that is what the pane is scrolled to: opening
  // an object runs the chain down to it, and the last few steps are the ones that say how it is held. Every
  // time the chain grows, which is also the pointer moving from rectangle to rectangle.
  val bottomObjectId = (tail ?: cutTail)?.lastOrNull()?.step?.objectId ?: target
  LaunchedEffect(bottomObjectId) {
    if (bottomObjectId != null) {
      snapshotFlow { listState.layoutInfo.totalItemsCount }.collect { count ->
        if (count > 0) {
          listState.animateScrollToItem(count - 1)
        }
      }
    }
  }
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column {
      drawn?.path?.faultyReference()?.let { SolvedLeak(it, onExplain) }
      // A row per object, drawn only where the pane has the room for it: a chain is as long as the heap dump
      // makes it, and the linked structures of a real one run to hundreds of steps — a pane that composed all
      // of them would take a minute to draw a chain nobody has scrolled to yet.
      LazyColumn(state = listState, contentPadding = PaddingValues(12.dp)) {
        item {
          // Where every chain starts, and the way back to the screen the window opens on.
          Column {
            PathRootRow(
              nextStrength = drawn?.path?.steps?.firstOrNull()?.step?.strength
                ?: cutTail?.firstOrNull()?.step?.strength,
              onOpen = onOpen,
              onCopyLink = onCopyLink
            )
            Spacer(Modifier.height(BLOCK_SPACING))
          }
        }
        if (drawn != null) {
          rootPathTrace(
            drawn = drawn,
            stronglyReachableByteCount = stronglyReachableByteCount,
            ways = ways,
            chosenWays = chosenWays,
            onChooseWay = onChooseWay,
            onOpen = onOpen,
            onCopyLink = onCopyLink,
            onExplain = onExplain
          )
        } else {
          noChainText(selection, summary, isWholeHeapDump, rootPath, hasTail = cutTail != null)?.let {
            item { Text(it, style = MaterialTheme.typography.bodySmall) }
          }
        }
        if (tail != null) {
          hoveredTail(
            steps = tail,
            isCut = false,
            stronglyReachableByteCount = stronglyReachableByteCount
          )
        } else if (cutTail != null) {
          // Nothing above it on screen is what holds it, so the end of it is the object being described here.
          hoveredTail(
            steps = cutTail,
            isCut = true,
            stronglyReachableByteCount = stronglyReachableByteCount
          )
        }
      }
    }
  }
}

/**
 * That the chain below is solved, and which reference solved it: two lines above everything else.
 *
 * The chain already marks that reference where it sits, and a reader still has to find it: a real chain is
 * tens of steps, this pane is scrolled to the bottom of it, and the answer is somewhere in the middle. So the
 * answer is also said where the eye starts, in the words the leaks screen names the leak with — a reader who
 * has got this far is looking for a name to go and grep for, not for another paragraph.
 *
 * Above the list rather than as its first row, because the list scrolls itself to the end every time the
 * pointer moves: a row at the top of it is a row that scrolls away.
 */
@Composable
private fun SolvedLeak(
  faultyReference: PathReference,
  onExplain: (Topic) -> Unit
) {
  Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
    Explain(Topic.FAULTY_REFERENCE, onExplain) {
      Text(LEAK_SOLVED, style = MaterialTheme.typography.labelSmall, color = MUTED_TEXT)
    }
    Text(
      faultyReference.leakLabel(),
      style = MaterialTheme.typography.bodyMedium,
      color = LeakStatus.STUCK.textColor
    )
    HorizontalDivider(Modifier.padding(top = 8.dp))
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
private fun LazyListScope.rootPathTrace(
  drawn: DrawnRootPath,
  stronglyReachableByteCount: Long,
  ways: Map<Int, List<RootPathWay>>,
  chosenWays: Map<Int, Int>,
  onChooseWay: (Int, Int) -> Unit,
  onOpen: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit,
  onExplain: (Topic) -> Unit
) {
  val steps = drawn.path.steps
  item {
    PathHeadRow(
      label = drawn.path.gcRootLabel.orEmpty(),
      reference = steps.first().step.reference,
      nextStrength = steps.first().step.strength,
      below = { WaysOfDetour(drawn, HEAD_INDEX, ways, chosenWays, onChooseWay, onExplain) }
    )
  }
  itemsIndexed(steps) { depth, step ->
    val next = steps.getOrNull(depth + 1)
    PathStepRow(
      step = step.step,
      // How this step points at the next one, which is what the next step was reached through.
      reference = next?.step?.reference,
      nextStrength = next?.step?.strength,
      stronglyReachableByteCount = stronglyReachableByteCount,
      onOpen = onOpen,
      onCopyLink = onCopyLink,
      role = when {
        // The object the details panel is about, whatever the pointer has added below it.
        next == null -> PathRole.TARGET
        step.isDominator -> PathRole.DOMINATOR
        else -> PathRole.STEP
      },
      below = { WaysOfDetour(drawn, depth, ways, chosenWays, onChooseWay, onExplain) }
    )
  }
}

/**
 * The chain for the rectangle under the pointer, drawn onto the end of the chain for the object shown.
 *
 * Lightly: what the reader wants of a rectangle they are only pointing at is which objects hold it and how
 * much each of those is worth, and everything else on a full row of the chain is four more lines of a pane
 * that is already the height of the window.
 */
private fun LazyListScope.hoveredTail(
  steps: List<RootPathStep>,
  /** Whether it runs on from the chain above or starts somewhere else, which the dots say. */
  isCut: Boolean,
  stronglyReachableByteCount: Long
) {
  item {
    Column {
      Spacer(Modifier.height(BLOCK_SPACING))
      if (isCut) {
        // Not below the object shown, so the chain above isn't the way to this one: the dots are that said
        // in the gutter, and the map itself is the rest of the answer.
        PathCutRow(nextStrength = steps.first().step.strength)
      }
    }
  }
  itemsIndexed(steps) { depth, step ->
    val next = steps.getOrNull(depth + 1)
    PathStepRow(
      step = step.step,
      reference = next?.step?.reference,
      nextStrength = next?.step?.strength,
      stronglyReachableByteCount = stronglyReachableByteCount,
      // Nothing to click: the pointer is on the map, and it leaving the map is what takes this away.
      onOpen = { _, _ -> },
      onCopyLink = {},
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
  onChooseWay: (Int, Int) -> Unit,
  onExplain: (Topic) -> Unit
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
    Explain(Topic.OTHER_WAYS, onExplain) {
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
 * What the section above the chain is called, when there is a reference to name under it.
 *
 * Past tense, and about the leak rather than about the reader: a chain with a faulty reference on it is
 * solved by the heap dump and the verdicts together, whoever set them and whenever.
 */
internal const val LEAK_SOLVED = "Leak solved"

/** Shown until a rectangle has been clicked, which is what this pane draws a chain for. */
internal const val NO_ROOT_PATH_YET = "Click a rectangle to see what holds it."

internal const val NO_ROOT_PATH_FOR_A_PILE =
  "Not one object, so there is no one chain holding it. Click it to reach the objects it stands for."

/** Shown while the walk up to the GC roots is still running. */
internal const val SEARCHING_ROOT_PATH = "Working out what holds it…"

internal const val NO_ROOT_PATH = "No chain from a GC root down to this object was found."

/** What the count between the arrows counts, after which of them is drawn: `2 of 3 ways from here`. */
internal const val WAYS_FROM_HERE = "ways from here"

internal const val PREVIOUS_WAY = "◂"
internal const val NEXT_WAY = "▸"

/** As wide as a class name plus what a step says about the object, and no wider: the map needs the room. */
internal val ROOT_PATH_WIDTH = 300.dp

/** Between the blocks of the pane — the whole heap dump, the chain, what the pointer added to it. */
private val BLOCK_SPACING = 4.dp

/** What the arrows for switching a stretch of the chain sit on, so they don't read as one of its objects. */
private val WAYS_BACKGROUND = Color(0x14000000)
