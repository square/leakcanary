package shark.explorer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import shark.explorer.RadialArc
import shark.explorer.RadialCell
import shark.explorer.RadialPresentation

/**
 * Draws an already laid out [RadialPresentation]: the root as the disk in the middle and each level as
 * the ring around the one before it, the way DaisyDisk draws a disk.
 *
 * The same gestures as [TreemapView] — a press selects the sector under the pointer, a double click
 * zooms into it — and the same single [Canvas], so the same holds for tests.
 */
@Composable
internal fun RadialView(
  presentation: RadialPresentation,
  scheme: CellColorScheme,
  selected: SelectedCell?,
  onSelect: (LayoutCell<Long>) -> Unit,
  /** The chain of nodes from the current root down to the one double clicked. */
  onZoomInto: (List<Long>) -> Unit,
  modifier: Modifier = Modifier
) {
  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val center = presentation.layout.center.let { Offset(it.x.toFloat(), it.y.toFloat()) }
  val sectors = remember(presentation, scheme, textMeasurer, density) {
    val colors = CellColors.of(scheme, presentation.cells)
    presentation.cells.map { it.measure(center, colors, textMeasurer, density) }
  }
  Box(modifier) {
    Canvas(
      Modifier.fillMaxSize()
        .pointerInput(presentation) {
          detectTapGestures(
            onPress = { offset -> presentation.cellAt(offset)?.let(onSelect) },
            onDoubleTap = { offset ->
              presentation.cellAt(offset)?.let { onZoomInto(presentation.layout.nodePathTo(it)) }
            }
          )
        }
    ) {
      sectors.forEach { sector -> drawSector(sector) }
      // Sectors don't overlap, unlike nested rectangles, but a selected one still has to be outlined
      // over its neighbours' borders to read as one shape.
      sectors.firstOrNull { it.selects == selected }?.let { sector ->
        drawPath(sector.path, color = SELECTION_COLOR, style = Stroke(width = SELECTION_WIDTH))
      }
    }
    NotExpandedBadge(presentation.truncatedNodeCount)
  }
}

private fun RadialPresentation.cellAt(offset: Offset): RadialCell<Long>? =
  layout.cellAt(offset.toTreemapPoint())

/** A sector with its shape built, its label measured and its colour resolved. */
private class MeasuredSector(
  val selects: SelectedCell,
  val path: Path,
  val color: Color,
  val borderColor: Color,
  val labelColor: Color,
  /** Null when the sector is too small for a readable label. */
  val label: TextLayoutResult?,
  /** Where the label goes once the canvas is rotated by [labelRotation] around the centre. */
  val labelTopLeft: Offset,
  val labelRotation: Float,
  val pivot: Offset
)

private fun PresentedCell<RadialCell<Long>>.measure(
  center: Offset,
  colors: CellColors,
  textMeasurer: TextMeasurer,
  density: Density
): MeasuredSector {
  val arc = cell.arc
  val padding = with(density) { LABEL_PADDING.toPx() }
  val ringWidth = (arc.outerRadius - arc.innerRadius).toFloat()
  // The middle of the ring is where a label reads best, and its length there is what the label has to
  // fit in — except in the middle, where the label runs across the disk rather than along a ring.
  val isCentre = arc.innerRadius == 0.0
  val labelWidth = if (isCentre) 2 * arc.outerRadius else arc.arcLength - 2 * padding
  val measured = if (labelWidth < with(density) { MIN_LABEL_WIDTH.toPx() } ||
    ringWidth - 2 * padding < with(density) { MIN_LABEL_HEIGHT.toPx() }
  ) {
    null
  } else {
    textMeasurer.measure(
      text = label,
      style = LABEL_STYLE,
      overflow = TextOverflow.Ellipsis,
      maxLines = 1,
      constraints = Constraints(maxWidth = labelWidth.toInt())
    ).takeIf { it.size.height <= ringWidth }
  }
  val labelSize = measured?.size
  val midRadius = ((arc.innerRadius + arc.outerRadius) / 2).toFloat()
  // A sector's label runs along its ring, so the canvas is rotated to the middle of the sweep and the
  // label drawn one mid radius away from the centre — flipped when that would leave it upside down.
  val alongTheRing = (arc.startAngle + arc.sweepAngle / 2 + QUARTER_TURN).toFloat()
  val isUpsideDown = !isCentre && normalized(alongTheRing) > QUARTER_TURN &&
    normalized(alongTheRing) < THREE_QUARTER_TURNS
  val rotation = when {
    isCentre -> 0f
    isUpsideDown -> alongTheRing - HALF_TURN
    else -> alongTheRing
  }
  val distance = when {
    isCentre -> 0f
    isUpsideDown -> midRadius
    else -> -midRadius
  }
  return MeasuredSector(
    selects = SelectedCell.of(cell.subject),
    path = arcPath(center, arc),
    color = colors.colorOf(this),
    borderColor = colors.border,
    labelColor = colors.label,
    label = measured,
    labelTopLeft = Offset(
      x = center.x - (labelSize?.width ?: 0) / 2f,
      y = center.y + distance - (labelSize?.height ?: 0) / 2f
    ),
    labelRotation = rotation,
    pivot = center
  )
}

/** The shape of one sector: the arc along its outer edge, back along its inner one, closed. */
private fun arcPath(
  center: Offset,
  arc: RadialArc
): Path {
  val path = Path()
  val outer = circle(center, arc.outerRadius.toFloat())
  val inner = circle(center, arc.innerRadius.toFloat())
  if (arc.sweepAngle >= RadialArc.FULL_CIRCLE) {
    // A whole ring, which the root's disk always is and an only child's sector can be. Two circles
    // with the hole punched out rather than an arc, which doesn't close.
    path.addOval(outer)
    if (arc.innerRadius > 0.0) {
      path.addOval(inner)
      path.fillType = PathFillType.EvenOdd
    }
    return path
  }
  path.arcTo(outer, arc.startAngle.toFloat(), arc.sweepAngle.toFloat(), forceMoveTo = true)
  if (arc.innerRadius > 0.0) {
    path.arcTo(
      inner,
      (arc.startAngle + arc.sweepAngle).toFloat(),
      -arc.sweepAngle.toFloat(),
      forceMoveTo = false
    )
  } else {
    path.lineTo(center.x, center.y)
  }
  path.close()
  return path
}

private fun circle(
  center: Offset,
  radius: Float
) = Rect(
  left = center.x - radius,
  top = center.y - radius,
  right = center.x + radius,
  bottom = center.y + radius
)

private fun DrawScope.drawSector(sector: MeasuredSector) {
  drawPath(sector.path, color = sector.color)
  drawPath(sector.path, color = sector.borderColor, style = Stroke(width = BORDER_WIDTH))
  sector.label?.let { label ->
    rotate(degrees = sector.labelRotation, pivot = sector.pivot) {
      drawText(textLayoutResult = label, color = sector.labelColor, topLeft = sector.labelTopLeft)
    }
  }
}

private fun normalized(degrees: Float): Float =
  (degrees % RadialArc.FULL_CIRCLE.toFloat() + RadialArc.FULL_CIRCLE.toFloat()) %
    RadialArc.FULL_CIRCLE.toFloat()

private const val QUARTER_TURN = 90f
private const val HALF_TURN = 180f
private const val THREE_QUARTER_TURNS = 270f
