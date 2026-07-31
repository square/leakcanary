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
import shark.explorer.HeapObjectSummary
import shark.explorer.IndependentPath
import shark.explorer.IndependentPaths
import shark.explorer.ObjectDominator

/**
 * Every way one object is held below its dominator, each drawn as a chain from the holder down to it.
 *
 * The chains are drawn by [PathStepRow], the same code as the shortest way a GC root reaches the object:
 * what makes these different is where they start, and that there are several of them.
 */
@Composable
internal fun PathsScreen(
  target: HeapObjectSummary?,
  dominator: ObjectDominator?,
  paths: IndependentPaths?,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(heading(target, dominator), style = MaterialTheme.typography.titleMedium)
      // Room for the whole explanation here, unlike in the panel, where it's behind a question mark.
      Text(INDEPENDENT_PATHS_HINT, style = MaterialTheme.typography.bodySmall)
      when {
        paths == null -> Text(SEARCHING_PATHS, style = MaterialTheme.typography.bodyMedium)
        paths.paths.isEmpty() -> Text(NO_PATH_FOUND, style = MaterialTheme.typography.bodyMedium)
        else -> paths.paths.forEachIndexed { index, path ->
          PathTrace(
            path = path,
            index = index,
            pathCount = paths.paths.size,
            dominator = dominator,
            coloring = coloring,
            onOpen = onOpen
          )
        }
      }
      if (paths != null && paths.hasMore) {
        Text(MORE_PATHS, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

private fun heading(
  target: HeapObjectSummary?,
  dominator: ObjectDominator?
): String = when {
  target == null -> INDEPENDENT_PATHS
  dominator == null -> "How ${target.label} is held"
  else -> "How ${target.label} is held below ${dominator.label}"
}

/** One way the object is held, from what the chain starts at down to it. */
@Composable
private fun PathTrace(
  path: IndependentPath,
  index: Int,
  pathCount: Int,
  dominator: ObjectDominator?,
  coloring: CellColoring,
  onOpen: (Long) -> Unit
) {
  Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
    Text("Path ${index + 1} of $pathCount", style = MaterialTheme.typography.labelMedium)
    PathHeadRow(
      label = path.headLabel(dominator),
      reference = path.steps.firstOrNull()?.reference,
      nextStrength = path.steps.firstOrNull()?.strength,
      nodeId = if (path.gcRootLabel == null) dominator?.nodeId else null,
      onOpen = onOpen
    )
    path.steps.forEachIndexed { depth, step ->
      val next = path.steps.getOrNull(depth + 1)
      PathStepRow(
        step = step,
        reference = next?.reference,
        nextStrength = next?.strength,
        coloring = coloring,
        onOpen = onOpen
      )
    }
  }
}

/**
 * What the chain starts at: the GC root that reaches it, or the dominator the paths run below.
 *
 * When steps were left out, the head stands for the elided chain rather than for the dominator, so it
 * says so — the reference under it belongs to the last of the objects left out, and the class it names
 * is how the reader can tell that's not the dominator's own field.
 */
private fun IndependentPath.headLabel(dominator: ObjectDominator?): String = when {
  gcRootLabel != null -> gcRootLabel!!
  hiddenStepCount > 0 ->
    "$ELLIPSIS $hiddenStepCount steps from ${dominator?.label ?: THE_DOMINATOR} to here"
  else -> dominator?.label ?: THE_DOMINATOR
}

/** The heading of the section spelling out the ways the object is held below its dominator. */
internal const val INDEPENDENT_PATHS = "Paths from the dominator"

internal const val INDEPENDENT_PATHS_HINT =
  "Every way this object is held, with the part they all share — everything above the dominator — " +
    "left out. The paths have no object in common in between either, which graph theory calls " +
    "internally vertex-disjoint, or independent: there are always at least two of them unless the " +
    "dominator points straight at this object, because one alone would mean whatever it goes through " +
    "is a closer dominator. Found greedily, so there can be more of them than are shown, and two " +
    "paths shown apart may well reference each other."

/** Shown while the search for the paths is still running. */
internal const val SEARCHING_PATHS = "Searching for the ways it's held…"

/** The dominator points straight at the object, so there is nothing between the two to spell out. */
internal const val NO_PATHS = "The dominator points straight at this object."

internal const val NO_PATH_FOUND = "No path from the dominator down to this object was found."

internal const val MORE_PATHS =
  "The search stopped here. There may be more ways this object is held."

/** Stands in for the dominator's name until the panel has read it, which is a beat behind the paths. */
private const val THE_DOMINATOR = "the dominator"

internal const val ELLIPSIS = "…"
