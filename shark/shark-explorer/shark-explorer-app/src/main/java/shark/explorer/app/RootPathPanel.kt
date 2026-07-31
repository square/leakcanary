package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import shark.explorer.HeapDominatorTreemap
import shark.explorer.RootPath

/**
 * The shortest way a GC root reaches whatever the window is describing, drawn as a chain down the pane.
 *
 * Beside the map rather than in the details panel, because a chain is a column of objects with something to
 * say about each of them and the panel is already one: both of them in one pane means one is always
 * scrolled off, and pointing at a rectangle is exactly when the two are worth reading together.
 *
 * The objects that dominate the one at the end are ringed and labelled, which is what ties the chain back to
 * the treemap: those are the rectangles it is drawn inside of, and the rest are only on the way to it.
 *
 * Everything here is read on the heap dump's thread — see [shark.explorer.HeapDominatorTreemap.rootPathTo] —
 * so [rootPath] arrives a little after whatever was pointed at changed.
 */
@Composable
internal fun RootPathPanel(
  /** What the window is describing: the cell under the pointer, or the one clicked if it's on none. */
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
      val summary = (selection as? Selection.Object)?.summary
      when {
        selection == null -> Text(NO_ROOT_PATH_YET, style = MaterialTheme.typography.bodySmall)
        // A pile of objects is held as many ways as it has objects in it, so there is no one chain.
        summary == null -> Text(NO_ROOT_PATH_FOR_A_PILE, style = MaterialTheme.typography.bodySmall)
        summary.objectId == HeapDominatorTreemap.ROOT_OBJECT_ID ->
          Text(EVERYTHING_STARTS_HERE, style = MaterialTheme.typography.bodySmall)
        rootPath == null -> Text(SEARCHING_ROOT_PATH, style = MaterialTheme.typography.bodySmall)
        rootPath.steps.isEmpty() -> Text(NO_ROOT_PATH, style = MaterialTheme.typography.bodySmall)
        else -> RootPathTrace(rootPath, coloring, onOpen)
      }
    }
  }
}

/** The chain itself: the GC root at the top, the object pointed at at the bottom. */
@Composable
private fun RootPathTrace(
  rootPath: RootPath,
  coloring: CellColoring,
  onOpen: (Long) -> Unit
) {
  Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
    PathHeadRow(
      label = rootPath.headLabel(),
      reference = rootPath.steps.first().step.reference,
      nextStrength = rootPath.steps.first().step.strength,
      // A GC root is a record of the heap dump rather than an object of it, so there is nowhere to go.
      nodeId = null,
      onOpen = onOpen
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
        }
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
    "why the treemap draws this object inside them."

/** Shown until a rectangle has been pointed at or clicked, which is what there is a chain for. */
internal const val NO_ROOT_PATH_YET = "Point at a rectangle to see what holds it."

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
