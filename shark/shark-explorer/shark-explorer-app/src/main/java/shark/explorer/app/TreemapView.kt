package shark.explorer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import shark.explorer.CellSubject
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
  /** The pixels read for the bitmaps of this presentation so far, by object id. */
  bitmapImages: Map<Long, ImageBitmap>,
  onSelect: (LayoutCell<Long>) -> Unit,
  /** The chain of nodes from the current root down to the one double clicked. */
  onZoomInto: (List<Long>) -> Unit,
  modifier: Modifier = Modifier
) {
  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  // Measuring a few hundred labels is the one part of drawing that isn't cheap, so it happens when
  // the presentation changes and never on a redraw.
  val cells = remember(presentation, coloring, bitmapImages, textMeasurer, density) {
    val colors = CellColors.of(coloring, presentation.cells)
    presentation.cells.map { presented ->
      presented.measure(
        colors = colors,
        textMeasurer = textMeasurer,
        density = density,
        isSubdivided = presentation.layout.isSubdivided(presented.cell),
        image = presented.imageOf(bitmapImages)
      )
    }
  }
  val edgeGrab = with(density) { EDGE_GRAB.toPx().toDouble() }
  val hoverChains = remember(presentation, edgeGrab) { HoverChains(presentation, edgeGrab) }
  var hoveredChain by remember(presentation) { mutableStateOf<String?>(null) }
  Box(modifier) {
    Canvas(
      Modifier.fillMaxSize()
        // Before the tap handler, so that a chain is read off the pointer wherever it is rather than
        // only where a gesture hasn't already claimed the events.
        .pointerInput(presentation) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              hoveredChain = when (event.type) {
                PointerEventType.Exit -> null
                PointerEventType.Move, PointerEventType.Enter, PointerEventType.Press ->
                  hoverChains.at(event.changes.first().position)
                else -> hoveredChain
              }
            }
          }
        }
        .pointerInput(presentation, edgeGrab) {
          detectTapGestures(
            // On press rather than on tap: with a double click handler installed, a tap is only
            // reported once the double click window has passed, which makes selection feel stuck.
            onPress = { offset -> presentation.cellAt(offset, edgeGrab)?.let(onSelect) },
            onDoubleTap = { offset ->
              presentation.cellAt(offset, edgeGrab)
                ?.let { onZoomInto(presentation.layout.nodePathTo(it)) }
            }
          )
        }
    ) {
      // Fills first and outlines after, all of them: a child covers every pixel of its parent, so a
      // parent drawn whole would leave nothing of the levels above showing. Outlining afterwards is
      // what draws nesting, and where a chain of single children shares an edge the outlines stack up
      // into a heavier line, which is the view saying there is more here than one rectangle.
      cells.forEach { cell -> drawFill(cell) }
      // Between the two, because a bitmap's pixels are the child rectangle covering it — its `byte[]`
      // before API 26, nothing at all after — and the image belongs over that rather than under it.
      // Still under every outline, so the nesting a bitmap sits in stays readable.
      cells.forEach { cell -> drawImage(cell) }
      cells.forEach { cell -> drawOutlineAndLabel(cell) }
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
    HoveredChain(hoveredChain)
    NotExpandedBadge(presentation.truncatedNodeCount)
  }
}

private fun TreemapPresentation.cellAt(
  offset: Offset,
  edgeGrab: Double
): TreemapCell<Long>? = layout.cellAt(offset.toTreemapPoint(), edgeGrab)

/**
 * Reads the names of the containers under the pointer off an already laid out presentation.
 *
 * Indexes the labels by node once, because hit testing runs on every pointer move and a presentation
 * of a real heap dump has thousands of cells.
 */
private class HoverChains(
  private val presentation: TreemapPresentation,
  private val edgeGrab: Double
) {

  private val labelByNode = HashMap<Long, String>(presentation.cells.size).apply {
    presentation.cells.forEach { presented ->
      val subject = presented.cell.subject
      if (subject is CellSubject.Node) {
        put(subject.node, presented.label)
      }
    }
  }

  /** Outermost container first, or null when [offset] is outside the treemap. */
  fun at(offset: Offset): String? {
    val cell = presentation.cellAt(offset, edgeGrab) ?: return null
    val chain = presentation.layout.nodePathTo(cell).mapNotNull { labelByNode[it] }
    // The path to a group ends at the node whose children it stands for, so it names itself. A node's
    // own bytes are the node, which the path already ends at.
    val tail = if (cell.subject is CellSubject.Group) {
      listOf(presentation.cells.first { it.cell === cell }.label)
    } else {
      emptyList()
    }
    val root = presentation.cells.first().label
    return (listOf(root) + chain + tail).joinToString(CHAIN_SEPARATOR)
  }
}

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
  val label: TextLayoutResult?,
  /** The bitmap's pixels and where they go, for a cell that is a bitmap the dump has the pixels of. */
  val image: ImageBitmap?,
  val imageOffset: IntOffset,
  val imageSize: IntSize
)

/** The pixels read for this cell, for a cell that stands for one bitmap. */
private fun PresentedCell<TreemapCell<Long>>.imageOf(images: Map<Long, ImageBitmap>): ImageBitmap? {
  val subject = cell.subject
  return if (subject is CellSubject.Node) images[subject.node] else null
}

private fun PresentedCell<TreemapCell<Long>>.measure(
  colors: CellColors,
  textMeasurer: TextMeasurer,
  density: Density,
  /** Whether something is drawn inside this cell, which would cover a label anyway. */
  isSubdivided: Boolean,
  image: ImageBitmap?
): MeasuredCell {
  val rect = cell.rect
  val labelPadding = with(density) { LABEL_PADDING.toPx() }
  val labelWidth = rect.width - 2 * labelPadding
  // An image covers the rectangle, so a label on it would be text over a picture. The chain at the
  // bottom of the view names what the pointer is on, which is where the name of a bitmap is read.
  val fitsALabel = !isSubdivided && image == null &&
    labelWidth >= with(density) { MIN_LABEL_WIDTH.toPx() } &&
    rect.height >= with(density) { MIN_LABEL_HEIGHT.toPx() }
  val topLeft = Offset(rect.left.toFloat(), rect.top.toFloat())
  val size = Size(rect.width.toFloat(), rect.height.toFloat())
  val bounds = image?.let { imageBounds(it, topLeft, size) }
  return MeasuredCell(
    selects = SelectedCell.of(cell.subject),
    topLeft = topLeft,
    size = size,
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
    },
    image = image,
    imageOffset = bounds?.first ?: IntOffset.Zero,
    imageSize = bounds?.second ?: IntSize.Zero
  )
}

private fun DrawScope.drawFill(cell: MeasuredCell) {
  drawRect(color = cell.color, topLeft = cell.topLeft, size = cell.size)
}

private fun DrawScope.drawImage(cell: MeasuredCell) {
  val image = cell.image ?: return
  drawImage(
    image = image,
    srcOffset = IntOffset.Zero,
    srcSize = IntSize(image.width, image.height),
    dstOffset = cell.imageOffset,
    dstSize = cell.imageSize
  )
}

private fun DrawScope.drawOutlineAndLabel(cell: MeasuredCell) {
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
