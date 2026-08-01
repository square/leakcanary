package shark.explorer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import shark.explorer.TreemapLayoutResult
import shark.explorer.TreemapPresentation

/**
 * Draws an already laid out [TreemapPresentation], filling the available space.
 *
 * A press reports the rectangle under the pointer, which is what the window goes to, and moving over one
 * reports it as hovered, which is what the chain beside the view describes. The names the map draws are
 * rectangles of their own for both of those — see [namedCellAt]. Everything is drawn into a single [Canvas],
 * so there are no per-rectangle composables: see this module's `AGENTS.md` for what that means for tests.
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
  /** The rectangle the pointer is on, which is outlined more lightly than the selected one. */
  hovered: SelectedCell?,
  /** The rectangle the pointer moved onto and where it is, or null when it moved onto none or left. */
  onHover: (PointedAt?) -> Unit,
  /** The rectangle pressed, which is where the window goes. */
  onClick: (LayoutCell<Long>) -> Unit,
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
        image = presented.imageOf(bitmapImages)
      )
    }
  }
  val edgeGrab = with(density) { EDGE_GRAB.toPx().toDouble() }
  // Where the pointer is, kept so that what it is on can be worked out again when the view is laid out
  // again under it. Null when it is outside the view.
  var pointerOffset: Offset? by remember { mutableStateOf(null) }
  // Zooming, resizing and switching shape all move the rectangles rather than the pointer, and no pointer
  // event follows: without this the panels would keep describing whatever was under it before, until the
  // mouse next moved.
  LaunchedEffect(presentation, cells, edgeGrab) {
    onHover(pointerOffset?.let { offset -> presentation.pointedAt(offset, cells, edgeGrab) })
  }
  Box(modifier) {
    Canvas(
      Modifier.fillMaxSize()
        // Before the tap handler, so that the rectangle under the pointer is read wherever it is rather
        // than only where a gesture hasn't already claimed the events.
        .pointerInput(presentation, cells, edgeGrab) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              when (event.type) {
                // A move, and not the enter that comes with it: a view composed under a pointer that
                // hasn't moved is sent an enter carrying the pointer's position, and describing what
                // that lands on would answer a rectangle nobody pointed at. Clicking a row of a list
                // puts the map where the row was, and the object clicked is what the panels are for.
                PointerEventType.Move -> {
                  val position = event.changes.first().position
                  pointerOffset = position
                  onHover(presentation.pointedAt(position, cells, edgeGrab))
                }
                PointerEventType.Exit -> {
                  pointerOffset = null
                  onHover(null)
                }
              }
            }
          }
        }
        .pointerInput(presentation, cells, edgeGrab) {
          detectTapGestures(
            // On press rather than on tap, which is immediate: with nothing waiting for a second click,
            // a tap handler would still hold every click for the double click window.
            onPress = { offset -> presentation.cellAt(offset, cells, edgeGrab)?.let(onClick) }
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
      cells.forEach { cell -> drawOutline(cell) }
      // And the current root's own children last, over whatever is nested inside them: the one level the
      // view names is the level being read, and its rectangles are the ones a click walks into.
      cells.forEach { cell -> if (cell.isRootChild) drawSeparator(cell) }
      cells.forEach { cell -> if (cell.isRootChild) drawLabel(cell) }
      // On top of everything: a selected or hovered rectangle that has children would otherwise have most
      // of its outline painted over by them. The hover outline goes under the selection's, so that the two
      // landing on one rectangle reads as selected rather than as hovered.
      if (hovered != selected) {
        cells.firstOrNull { it.selects == hovered }?.let { cell ->
          drawRect(
            color = HOVER_COLOR,
            topLeft = cell.topLeft,
            size = cell.size,
            style = Stroke(width = HOVER_WIDTH)
          )
        }
      }
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

private fun TreemapPresentation.cellAt(
  offset: Offset,
  /** This presentation's cells as measured for drawing, which is what says where the names ended up. */
  cells: List<MeasuredCell>,
  edgeGrab: Double
): TreemapCell<Long>? = cells.namedCellAt(offset) ?: layout.cellAt(offset.toTreemapPoint(), edgeGrab)

/** What the pointer is on at [offset], with the offset kept: the card following the pointer needs both. */
private fun TreemapPresentation.pointedAt(
  offset: Offset,
  cells: List<MeasuredCell>,
  edgeGrab: Double
): PointedAt? = cellAt(offset, cells, edgeGrab)?.let { PointedAt(cell = it, offset = offset) }

/**
 * The rectangle whose name is drawn at [offset], or null where no name is drawn.
 *
 * A rectangle's children cover every pixel of it, so the name the map gives it is drawn over them, and the
 * plate under that name is the one piece of a subdivided rectangle that is still the rectangle itself. So
 * pointing at a name means the thing named rather than whichever descendant happens to be under the
 * lettering, and clicking one walks into the level the map is divided into rather than to the bottom of it.
 * Everywhere else the innermost rectangle wins, as before: see [TreemapLayoutResult.cellAt].
 *
 * Last match first, which is topmost first: the names are painted in this order, so where two of them
 * overlap the one drawn last is the one being pointed at.
 */
private fun List<MeasuredCell>.namedCellAt(offset: Offset): TreemapCell<Long>? =
  lastOrNull { it.label?.plate?.contains(offset) == true }?.cell

/** A rectangle with its label measured and its colour resolved, so that drawing does no work. */
private class MeasuredCell(
  /** The cell as laid out, which is what pointing at this rectangle reports. */
  val cell: TreemapCell<Long>,
  val selects: SelectedCell,
  val topLeft: Offset,
  val size: Size,
  val color: Color,
  val borderColor: Color,
  val outline: Stroke,
  /** Whether this rectangle is one of the current root's own children, which are the named level. */
  val isRootChild: Boolean,
  /** Null when the rectangle is too small for a readable label, or when it isn't a named one. */
  val label: MeasuredLabel?,
  /** The bitmap's pixels and where they go, for a cell that is a bitmap the dump has the pixels of. */
  val image: ImageBitmap?,
  val imageOffset: IntOffset,
  val imageSize: IntSize
)

/**
 * A name drawn on a rectangle, and the plate it is drawn on.
 *
 * One value holding both what is painted and where, because the plate is a hit target as well as a
 * background — see [namedCellAt] — and a plate the pointer answers to that isn't the plate on screen would
 * be the view lying about where its own names are.
 */
private class MeasuredLabel(
  val text: TextLayoutResult,
  val color: Color,
  val textTopLeft: Offset,
  val plate: Rect
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
  image: ImageBitmap?
): MeasuredCell {
  val rect = cell.rect
  val labelPadding = with(density) { LABEL_PADDING.toPx() }
  val labelWidth = rect.width - 2 * labelPadding
  // Only the current root's own children are named, and every one of them that has the room for it is,
  // nested contents and pictures included: the label goes over those rather than under them.
  //
  // Naming every rectangle instead is what made a treemap of a real dump unreadable — a hundred class
  // names in half a dozen levels, each of them the name of something the level below it covers. The one
  // level being read is named on the map, and the chain beside it names what the pointer is on.
  val isRootChild = cell.depth == ROOT_CHILD_DEPTH
  val fitsALabel = isRootChild &&
    labelWidth >= with(density) { MIN_LABEL_WIDTH.toPx() } &&
    rect.height >= with(density) { MIN_LABEL_HEIGHT.toPx() }
  val topLeft = Offset(rect.left.toFloat(), rect.top.toFloat())
  val size = Size(rect.width.toFloat(), rect.height.toFloat())
  val bounds = image?.let { imageBounds(it, topLeft, size) }
  return MeasuredCell(
    cell = cell,
    selects = SelectedCell.of(cell.subject),
    topLeft = topLeft,
    size = size,
    color = colors.colorOf(this),
    borderColor = colors.borderOf(this),
    outline = outlineOf(content),
    isRootChild = isRootChild,
    label = if (fitsALabel) {
      textMeasurer.measure(
        text = label,
        style = LABEL_STYLE,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = labelWidth.toInt())
      ).asLabel(
        color = colors.label,
        textTopLeft = topLeft + Offset(labelPadding, labelPadding),
        cellBounds = Rect(offset = topLeft, size = size)
      )
    } else {
      null
    },
    image = image,
    imageOffset = bounds?.first ?: IntOffset.Zero,
    imageSize = bounds?.second ?: IntSize.Zero
  )
}

/** A measured name placed at [textTopLeft], with the plate it sits on around it. */
private fun TextLayoutResult.asLabel(
  color: Color,
  textTopLeft: Offset,
  /** The rectangle being named, which the plate stays inside. */
  cellBounds: Rect
) = MeasuredLabel(
  text = this,
  color = color,
  textTopLeft = textTopLeft,
  plate = Rect(
    offset = textTopLeft - Offset(LABEL_PLATE_PADDING, LABEL_PLATE_PADDING),
    size = Size(
      size.width + 2 * LABEL_PLATE_PADDING,
      size.height + 2 * LABEL_PLATE_PADDING
    )
  )
    // A rectangle only a line of text tall has less room than the plate wants, and the overhang would be
    // drawn on the sibling below it as well as answering the pointer for it. The lettering can still
    // overflow, as it always could; what stands for the rectangle can't reach outside it.
    .intersect(cellBounds)
)

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

private fun DrawScope.drawOutline(cell: MeasuredCell) {
  drawRect(
    color = cell.borderColor,
    topLeft = cell.topLeft,
    size = cell.size,
    style = cell.outline
  )
}

/**
 * The line around one of the current root's own children, which is the boundary the map is read by.
 *
 * Heavier than any outline inside it and drawn over all of them: the levels below cover their parent
 * exactly, so without this the edge between two children looks like every other edge on the map.
 */
private fun DrawScope.drawSeparator(cell: MeasuredCell) {
  drawRect(
    color = ROOT_CHILD_BORDER_COLOR,
    topLeft = cell.topLeft,
    size = cell.size,
    style = Stroke(width = ROOT_CHILD_BORDER_WIDTH)
  )
}

/**
 * What one of those children is, over whatever is nested inside it.
 *
 * On a translucent plate rather than straight onto the map, because the text sits over rectangles, bitmaps
 * and outlines it has no say over: solid text on a washed out background is readable against all of them
 * while still letting what it covers show through. That plate is also what pointing at the name points at,
 * see [namedCellAt].
 */
private fun DrawScope.drawLabel(cell: MeasuredCell) {
  val label = cell.label ?: return
  drawRect(color = LABEL_PLATE_COLOR, topLeft = label.plate.topLeft, size = label.plate.size)
  drawText(textLayoutResult = label.text, color = label.color, topLeft = label.textTopLeft)
}
