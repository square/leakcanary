package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectSummary
import shark.explorer.RootPath
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

/**
 * The shortest way a GC root reaches whatever the window is describing, drawn as a chain down the pane.
 *
 * Beside the map rather than in the details panel, because a chain is a column of objects with something to
 * say about each of them and the panel is already one: both of them in one pane means one is always
 * scrolled off, and pointing at a rectangle is exactly when the two are worth reading together.
 *
 * The objects that dominate the one at the end are ringed and labelled, which is what ties the chain back to
 * the treemap: those are the rectangles it is drawn inside of, and the rest are only on the way to it. Every
 * object of it is a click away from the map going there, so this is also the way back out — the ringed steps
 * are the nesting the map zoomed through, in the order it zoomed through them.
 *
 * Everything here is read on the heap dump's thread — see [shark.explorer.HeapDominatorTreemap.rootPathTo] —
 * so [rootPath] arrives a little after whatever was pointed at changed.
 */
@Composable
internal fun RootPathPanel(
  /** The cell clicked, which is what this pane describes whatever the pointer is doing. */
  selection: Selection?,
  /** Null until the walk up to the GC roots has come back. */
  rootPath: RootPath?,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      SectionHeading(ROOT_PATH, ROOT_PATH_HINT)
      RootPathContent(selection, rootPath, coloring, onOpen, PathDetail.FULL)
    }
  }
}

/**
 * The same chain for the rectangle under the pointer, floating over the one for the rectangle clicked.
 *
 * Floating rather than replacing it, because the two answer different questions: what is this thing I am
 * looking at, and what is that thing over there. Moving the pointer off the map puts the pane back to the
 * first without anything having to be read again.
 *
 * Says what the object is at the top, since the details panel no longer follows the pointer: a rectangle is
 * pointed at to find out what it is, and reading that off a panel at the far edge of the window while the
 * chain explaining it sits next to the map is two places to look at once.
 */
@Composable
internal fun HoveredPathPanel(
  selection: Selection?,
  rootPath: RootPath?,
  coloring: CellColoring,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier,
    color = MaterialTheme.colorScheme.surface,
    shadowElevation = HOVER_PANEL_ELEVATION
  ) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      HoveredObjectHeader(selection, coloring)
      // Nothing to click on: the pointer is on the map, and it leaving the map is what closes this.
      RootPathContent(selection, rootPath, coloring, onOpen = {}, detail = PathDetail.BRIEF)
    }
  }
}

/** What the pointer is on, which is what the details panel says about the object clicked. */
@Composable
private fun HoveredObjectHeader(
  selection: Selection?,
  coloring: CellColoring
) {
  val summary = (selection as? Selection.Object)?.summary ?: return
  Text(summary.label, style = MaterialTheme.typography.titleSmall)
  Text(summary.className, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
  if (summary.objectId != HeapDominatorTreemap.ROOT_OBJECT_ID) {
    Text(objectIdText(summary.objectId), style = MaterialTheme.typography.bodySmall)
  }
  summary.headline?.let { headline ->
    Text(headline, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
  }
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(Modifier.size(SWATCH_SIZE).background(legendColor(coloring, summary.strength)))
    Text(summary.strength.reachabilityText, style = MaterialTheme.typography.bodySmall)
  }
  // The same numbers the details panel gives a labelled row each, on two lines: this pane is over the map
  // and only for as long as the pointer stays still, so what it costs in height is what it costs the chain.
  Text(summary.retainedText(), style = MaterialTheme.typography.bodySmall)
  Text(summary.shallowText(), style = MaterialTheme.typography.bodySmall)
}

private fun HeapObjectSummary.retainedText(): String =
  "Retains ${formatByteSize(retainedSize)} in ${formatObjectCount(retainedCount)}"

private fun HeapObjectSummary.shallowText(): String =
  "${formatByteSize(shallowSize)} of its own, dominates ${formatObjectCount(dominatedObjectCount)}"

/** The chain, or what there is to say instead when there is none to draw. */
@Composable
private fun RootPathContent(
  selection: Selection?,
  rootPath: RootPath?,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  detail: PathDetail
) {
  val summary = (selection as? Selection.Object)?.summary
  when {
    // A floating chain is only ever drawn for a rectangle the pointer is on, so it having no object yet
    // means the read for that rectangle hasn't come back rather than that nothing has been clicked.
    selection == null -> Text(
      if (detail == PathDetail.FULL) NO_ROOT_PATH_YET else SEARCHING_ROOT_PATH,
      style = MaterialTheme.typography.bodySmall
    )
    // A pile of objects is held as many ways as it has objects in it, so there is no one chain.
    summary == null -> Text(NO_ROOT_PATH_FOR_A_PILE, style = MaterialTheme.typography.bodySmall)
    summary.objectId == HeapDominatorTreemap.ROOT_OBJECT_ID ->
      Text(EVERYTHING_STARTS_HERE, style = MaterialTheme.typography.bodySmall)
    rootPath == null -> Text(SEARCHING_ROOT_PATH, style = MaterialTheme.typography.bodySmall)
    rootPath.steps.isEmpty() -> Text(NO_ROOT_PATH, style = MaterialTheme.typography.bodySmall)
    else -> RootPathTrace(rootPath, coloring, onOpen, detail)
  }
}

/** The chain itself: the GC root at the top, the object pointed at at the bottom. */
@Composable
private fun RootPathTrace(
  rootPath: RootPath,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  detail: PathDetail
) {
  Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
    PathHeadRow(
      label = rootPath.headLabel(),
      reference = rootPath.steps.first().step.reference,
      nextStrength = rootPath.steps.first().step.strength,
      // A GC root is a record of the heap dump rather than an object of it, so there is nowhere to go.
      nodeId = null,
      onOpen = onOpen,
      detail = detail
    )
    rootPath.steps.forEachIndexed { depth, step ->
      val next = rootPath.steps.getOrNull(depth + 1)
      PathStepRow(
        step = step.step,
        // How this step points at the next one, which is what the next step was reached through.
        reference = next?.step?.reference,
        nextStrength = next?.step?.strength,
        coloring = coloring,
        onOpen = onOpen,
        role = when {
          next == null -> PathRole.TARGET
          step.isDominator -> PathRole.DOMINATOR
          else -> PathRole.STEP
        },
        detail = detail
      )
    }
  }
}

/**
 * Which GC root the chain starts at, and how many steps below it were left out.
 *
 * As on the paths screen, the reference drawn under this row then belongs to the last of the objects left
 * out rather than to the root, and the class it names is how the reader can tell.
 */
private fun RootPath.headLabel(): String = if (hiddenStepCount == 0) {
  gcRootLabel.orEmpty()
} else {
  "${gcRootLabel.orEmpty()}, then $ELLIPSIS $hiddenStepCount steps"
}

/** The heading of the pane, which is the question the chain answers. */
internal const val ROOT_PATH = "Held from a GC root"

internal const val ROOT_PATH_HINT =
  "The shortest chain of references from a GC root down to this object, which is why it is still in " +
    "memory. Shortest in steps, so it's the plainest of the ways it's held rather than one of the ways " +
    "round; the paths from the dominator are the rest of them. The steps ringed and marked as dominators " +
    "are the ones that would free it — every chain from a GC root goes through each of those, which is " +
    "why the treemap draws this object inside them. Click any object of it to go there."

/** Shown until a rectangle has been clicked, which is what this pane draws a chain for. */
internal const val NO_ROOT_PATH_YET = "Click a rectangle to see what holds it."

internal const val NO_ROOT_PATH_FOR_A_PILE =
  "Not one object, so there is no one chain holding it. Zoom in to reach the objects it stands for."

/** The virtual root above the whole heap dump, which every chain starts below rather than reaching. */
internal const val EVERYTHING_STARTS_HERE =
  "The whole heap dump. Every chain below starts at one of its GC roots."

/** Shown while the walk up to the GC roots is still running. */
internal const val SEARCHING_ROOT_PATH = "Working out what holds it…"

internal const val NO_ROOT_PATH = "No chain from a GC root down to this object was found."

/** As wide as a class name plus what a step says about the object, and no wider: the map needs the room. */
internal val ROOT_PATH_WIDTH = 300.dp

/** How far inside the pane the floating chain sits, which is what makes it read as being over it. */
internal val HOVER_PANEL_INSET = 6.dp

private val HOVER_PANEL_ELEVATION = 8.dp
