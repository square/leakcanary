package shark.explorer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import shark.explorer.PresentedCell
import shark.explorer.TreemapCell
import shark.explorer.TreemapPoint
import shark.explorer.TreemapPresentation

/**
 * Draws an already laid out [TreemapPresentation], filling the available space.
 *
 * A press selects the rectangle under the pointer and a double click zooms into it. Everything is
 * drawn into a single [Canvas], so there are no per-rectangle composables: see this module's
 * `AGENTS.md` for what that means for tests.
 *
 * Takes a presentation rather than a tree, because laying a treemap out reads the heap dump for every
 * visible label, and that has to have happened on the heap dump's own thread before we get here.
 */
@Composable
internal fun TreemapView(
  presentation: TreemapPresentation,
  scheme: CellColorScheme,
  selected: SelectedCell?,
  onSelect: (TreemapCell<Long>) -> Unit,
  /** The chain of nodes from the current root down to the one double clicked. */
  onZoomInto: (List<Long>) -> Unit,
  modifier: Modifier = Modifier
) {
  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  // Measuring a few hundred labels is the one part of drawing that isn't cheap, so it happens when
  // the presentation changes and never on a redraw.
  val cells = remember(presentation, scheme, textMeasurer, density) {
    val colors = CellColors.of(scheme, presentation)
    presentation.cells.map { it.measure(colors, textMeasurer, density) }
  }
  Box(modifier) {
    Canvas(
      Modifier.fillMaxSize()
        .pointerInput(presentation) {
          detectTapGestures(
            // On press rather than on tap: with a double click handler installed, a tap is only
            // reported once the double click window has passed, which makes selection feel stuck.
            onPress = { offset -> presentation.cellAt(offset)?.let(onSelect) },
            onDoubleTap = { offset -> onZoomInto(presentation.nodePathAt(offset)) }
          )
        }
    ) {
      cells.forEach { cell -> drawCell(cell) }
      // On top of everything: a selected rectangle that has children would otherwise have most of its
      // outline painted over by them.
      cells.firstOrNull { it.selects == selected }?.let { cell ->
        drawRect(
          color = SELECTION_COLOR,
          topLeft = cell.topLeft,
          size = cell.size,
          style = Stroke(width = SELECTION_WIDTH)
        )
      }
    }
    if (presentation.truncatedNodeCount > 0) {
      Surface(
        Modifier.align(Alignment.BottomEnd).padding(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
      ) {
        val count = presentation.truncatedNodeCount
        Text(
          if (count == 1) "1 node not expanded" else "$count nodes not expanded",
          Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          style = MaterialTheme.typography.labelSmall
        )
      }
    }
  }
}

/**
 * Which rectangle is selected, in terms that outlive a relayout.
 *
 * An object's own id for a rectangle that is a node; the parent's id for the rectangle standing for
 * the children it didn't draw, since two groups never share a parent. Resizing the window lays the
 * treemap out again, and the selection has to survive that.
 */
internal data class SelectedCell(
  val objectId: Long,
  val isGroup: Boolean
) {
  companion object {
    fun of(cell: TreemapCell<Long>): SelectedCell = when (cell) {
      is TreemapCell.Node -> SelectedCell(cell.node, isGroup = false)
      is TreemapCell.Group -> SelectedCell(cell.parent, isGroup = true)
    }
  }
}

private fun TreemapPresentation.cellAt(offset: Offset): TreemapCell<Long>? =
  layout.cellAt(offset.toTreemapPoint())

/** The nodes containing [offset], outermost first, minus the root the treemap is already at. */
private fun TreemapPresentation.nodePathAt(offset: Offset): List<Long> =
  layout.cellPathAt(offset.toTreemapPoint())
    .filterIsInstance<TreemapCell.Node<Long>>()
    .drop(1)
    .map { it.node }

private fun Offset.toTreemapPoint() = TreemapPoint(x.toDouble(), y.toDouble())

/** A rectangle with its label measured and its colour resolved, so that drawing does no work. */
private class MeasuredCell(
  val selects: SelectedCell,
  val topLeft: Offset,
  val size: Size,
  val color: Color,
  val borderColor: Color,
  val labelColor: Color,
  val labelOffset: Offset,
  /** Null when the rectangle is too small for a readable label. */
  val label: TextLayoutResult?
)

private fun PresentedCell.measure(
  colors: CellColors,
  textMeasurer: TextMeasurer,
  density: Density
): MeasuredCell {
  val rect = cell.rect
  val labelPadding = with(density) { LABEL_PADDING.toPx() }
  val labelWidth = rect.width - 2 * labelPadding
  val fitsALabel = labelWidth >= with(density) { MIN_LABEL_WIDTH.toPx() } &&
    rect.height >= with(density) { MIN_LABEL_HEIGHT.toPx() }
  return MeasuredCell(
    selects = SelectedCell.of(cell),
    topLeft = Offset(rect.left.toFloat(), rect.top.toFloat()),
    size = Size(rect.width.toFloat(), rect.height.toFloat()),
    color = colors.colorOf(this),
    borderColor = colors.border,
    labelColor = colors.label,
    labelOffset = Offset(labelPadding, labelPadding),
    label = if (fitsALabel) {
      textMeasurer.measure(
        text = label,
        style = LABEL_STYLE,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = labelWidth.toInt())
      )
    } else {
      null
    }
  )
}

private fun DrawScope.drawCell(cell: MeasuredCell) {
  drawRect(color = cell.color, topLeft = cell.topLeft, size = cell.size)
  drawRect(
    color = cell.borderColor,
    topLeft = cell.topLeft,
    size = cell.size,
    style = Stroke(width = BORDER_WIDTH)
  )
  cell.label?.let { label ->
    drawText(
      textLayoutResult = label,
      color = cell.labelColor,
      topLeft = cell.topLeft + cell.labelOffset
    )
  }
}

/**
 * The layout thresholds in dp. [TreemapLayout][shark.explorer.TreemapLayout] works in pixels, so they
 * have to be scaled or a rectangle that's big enough to subdivide on one display is too small on
 * another.
 */
internal val MIN_SUBDIVIDE_WIDTH = 40.dp
internal val MIN_SUBDIVIDE_HEIGHT = 24.dp
internal val MIN_DRAW_SIZE = 3.dp
internal val HEADER_HEIGHT = 18.dp

private val LABEL_PADDING = 3.dp
private val MIN_LABEL_WIDTH = 24.dp
private val MIN_LABEL_HEIGHT = 13.dp
private const val BORDER_WIDTH = 1f
private const val SELECTION_WIDTH = 3f
private val LABEL_STYLE = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
