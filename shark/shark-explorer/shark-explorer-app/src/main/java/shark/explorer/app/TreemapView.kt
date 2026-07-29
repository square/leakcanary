package shark.explorer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import shark.explorer.LayoutCell
import shark.explorer.PresentedCell
import shark.explorer.TreemapCell
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
  coloring: CellColoring,
  selected: SelectedCell?,
  onSelect: (LayoutCell<Long>) -> Unit,
  /** The chain of nodes from the current root down to the one double clicked. */
  onZoomInto: (List<Long>) -> Unit,
  modifier: Modifier = Modifier
) {
  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  // Measuring a few hundred labels is the one part of drawing that isn't cheap, so it happens when
  // the presentation changes and never on a redraw.
  val cells = remember(presentation, coloring, textMeasurer, density) {
    val colors = CellColors.of(coloring, presentation.cells)
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
            onDoubleTap = { offset ->
              presentation.cellAt(offset)?.let { onZoomInto(presentation.layout.nodePathTo(it)) }
            }
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
    NotExpandedBadge(presentation.truncatedNodeCount)
  }
}

private fun TreemapPresentation.cellAt(offset: Offset): TreemapCell<Long>? =
  layout.cellAt(offset.toTreemapPoint())

/** A rectangle with its label measured and its colour resolved, so that drawing does no work. */
private class MeasuredCell(
  val selects: SelectedCell,
  val topLeft: Offset,
  val size: Size,
  val color: Color,
  val borderColor: Color,
  val outline: Stroke,
  val labelColor: Color,
  val labelOffset: Offset,
  /** Null when the rectangle is too small for a readable label. */
  val label: TextLayoutResult?
)

private fun PresentedCell<TreemapCell<Long>>.measure(
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
    selects = SelectedCell.of(cell.subject),
    topLeft = Offset(rect.left.toFloat(), rect.top.toFloat()),
    size = Size(rect.width.toFloat(), rect.height.toFloat()),
    color = colors.colorOf(this),
    borderColor = colors.borderOf(this),
    outline = outlineOf(content),
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
    style = cell.outline
  )
  cell.label?.let { label ->
    drawText(
      textLayoutResult = label,
      color = cell.labelColor,
      topLeft = cell.topLeft + cell.labelOffset
    )
  }
}
