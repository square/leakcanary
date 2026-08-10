package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * One of the three columns an object is read through, which are three questions about it.
 *
 * Left to right that is where the object came from, where it is, and what it is keeping alive — see
 * `notes/decisions.md`. Each folds away, because which of the three you are working in changes with what
 * you are chasing: a chain thirty steps long wants the window, and so does a treemap.
 */
internal enum class Pane(val paneName: String) {

  /** Why the object is in memory: the chain a GC root reaches it by. */
  CHAIN("What holds it"),

  /** What the object holds: the dominator tree, rooted at the object itself. */
  VIEW("What it holds"),

  /** What the object is: its size, what the inspectors make of it, its fields. */
  DETAILS("What it is")
}

/**
 * How wide the three columns are and which of them are folded away.
 *
 * Held per window rather than per tab: the shape of the window is how someone has set their desk up for
 * the job at hand, and having it change under them as they switch tabs would be the window rearranging
 * itself for reasons of its own.
 */
@Stable
internal class PanesState {

  var chainWidth by mutableStateOf(ROOT_PATH_WIDTH)
  var detailsWidth by mutableStateOf(DETAILS_WIDTH)

  private var foldedChain by mutableStateOf(false)
  private var foldedView by mutableStateOf(false)
  private var foldedDetails by mutableStateOf(false)

  fun isFolded(pane: Pane): Boolean = when (pane) {
    Pane.CHAIN -> foldedChain
    Pane.VIEW -> foldedView
    Pane.DETAILS -> foldedDetails
  }

  fun toggleFold(pane: Pane) {
    when (pane) {
      Pane.CHAIN -> foldedChain = !foldedChain
      Pane.VIEW -> foldedView = !foldedView
      Pane.DETAILS -> foldedDetails = !foldedDetails
    }
  }

  /**
   * Which pane takes whatever width the fixed ones leave, or null when all three are folded.
   *
   * The view by preference, since that is the one drawn to the size it is given. Folding it hands the
   * room to the details, and then to the chain, so that folding a pane always widens something rather
   * than leaving a stripe of empty window.
   */
  val filling: Pane?
    get() = when {
      !foldedView -> Pane.VIEW
      !foldedDetails -> Pane.DETAILS
      !foldedChain -> Pane.CHAIN
      else -> null
    }

  /** Widens or narrows [pane] by [delta], within what leaves the window readable. */
  fun resize(
    pane: Pane,
    delta: Dp
  ) {
    when (pane) {
      Pane.CHAIN -> chainWidth = (chainWidth + delta).coerceIn(MIN_PANE_WIDTH, MAX_PANE_WIDTH)
      Pane.DETAILS -> detailsWidth = (detailsWidth + delta).coerceIn(MIN_PANE_WIDTH, MAX_PANE_WIDTH)
      // The view is never a width of its own: it is whatever the other two leave.
      Pane.VIEW -> Unit
    }
  }
}

/** The name of a pane and the control that folds it away, along the top of it. */
@Composable
internal fun PaneHeader(
  pane: Pane,
  modifier: Modifier = Modifier,
  onFold: () -> Unit
) {
  Row(
    modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      pane.paneName,
      Modifier.weight(1f),
      style = MaterialTheme.typography.labelSmall
    )
    FoldButton(pane, onFold)
  }
}

/** The control that folds a pane away, which the view keeps in the row of controls above it. */
@Composable
internal fun FoldButton(
  pane: Pane,
  onFold: () -> Unit
) {
  Hint("Fold ${pane.paneName.replaceFirstChar { it.lowercase() }} away.") {
    Text(
      FOLD,
      Modifier.clickable(onClick = onFold).padding(4.dp),
      style = MaterialTheme.typography.labelSmall
    )
  }
}

/**
 * A folded pane: no width at all beyond the button that brings it back.
 *
 * Which is the one thing a folded pane has to keep — a pane folded away with nothing left on screen is a
 * pane nobody can get back without knowing it was ever there.
 */
@Composable
internal fun FoldedPane(
  pane: Pane,
  onUnfold: () -> Unit
) {
  Column(
    Modifier.width(FOLDED_PANE_WIDTH).fillMaxHeight()
      .background(MaterialTheme.colorScheme.surfaceVariant),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Hint("Show ${pane.paneName.replaceFirstChar { it.lowercase() }} again.") {
      Text(
        UNFOLD,
        Modifier.clickable(onClick = onUnfold).padding(vertical = 6.dp, horizontal = 4.dp),
        style = MaterialTheme.typography.labelSmall
      )
    }
  }
}

/**
 * The edge between two panes, dragged to move it.
 *
 * Wider than the line it draws, because a 1 px line is not something a pointer can be expected to hit —
 * the same reason the map's own containers have an [EDGE_GRAB].
 */
@Composable
internal fun PaneDivider(onDrag: (Dp) -> Unit) {
  val density = LocalDensity.current
  Box(
    Modifier
      .width(DIVIDER_GRAB)
      .fillMaxHeight()
      .pointerHoverIcon(RESIZE_CURSOR)
      .draggable(
        orientation = Orientation.Horizontal,
        state = rememberDraggableState { delta -> onDrag(with(density) { delta.toDp() }) }
      )
  ) {
    Box(
      Modifier.width(DIVIDER_LINE).fillMaxHeight().align(Alignment.Center)
        .background(MaterialTheme.colorScheme.outlineVariant)
    )
  }
}

/** What the window shows in the middle when the last tab has been closed. */
@Composable
internal fun NoTabOpen(modifier: Modifier = Modifier) {
  Box(modifier, contentAlignment = Alignment.Center) {
    Text(NO_TAB_OPEN, style = MaterialTheme.typography.bodyMedium)
  }
}

private val RESIZE_CURSOR = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

private const val FOLD = "⊟"
private const val UNFOLD = "⊞"

/** Narrow enough to be a margin rather than a column, wide enough to hit the button on it. */
private val FOLDED_PANE_WIDTH = 22.dp

private val DIVIDER_GRAB = 8.dp
private val DIVIDER_LINE = 1.dp

/** Below this a pane is too narrow to read a class name in, and above it the view is the one squeezed. */
private val MIN_PANE_WIDTH = 160.dp
private val MAX_PANE_WIDTH = 720.dp
