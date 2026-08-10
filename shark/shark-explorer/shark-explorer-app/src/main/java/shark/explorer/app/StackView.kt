package shark.explorer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import shark.explorer.CellContent
import shark.explorer.CellSubject
import shark.explorer.LayoutCell
import shark.explorer.PresentedCell
import shark.explorer.StackCell
import shark.explorer.StackPresentation
import shark.explorer.TreemapPoint
import shark.explorer.formatByteSize

/**
 * Draws an already laid out [StackPresentation]: the node it's rooted at as the row across the top and
 * every level below it as the row under the one before, the way a profiler draws a call tree upside
 * down.
 *
 * The same gestures as [TreemapView] — a press reports the block under the pointer, moving over one
 * reports it as hovered — and the same single [Canvas], so the same holds for tests. What it adds is a
 * scroll: a stack is as deep as the tree it drew, so unlike the other two shapes it does not fit the
 * window, and the pointer's coordinates and the block under them are a scroll offset apart.
 *
 * Every block that has the width for it carries its own name and size, which the treemap can't do — a
 * rectangle there is covered by its children, and naming every level of it was unreadable. Here a level
 * is a row of its own, so there is nothing to cover and nothing to leave out.
 */
@Composable
internal fun StackView(
  presentation: StackPresentation,
  coloring: CellColoring,
  /** Which of the objects drawn are leaking, for the colouring that shades them. */
  shading: LeakShading,
  selected: SelectedCell?,
  /** The block the pointer is on, which is outlined more lightly than the selected one. */
  hovered: SelectedCell?,
  /** The block the pointer moved onto and where it is, or null when it moved onto none or left. */
  onHover: (PointedAt?) -> Unit,
  /** The block pressed, which is where the window goes, and which tab it asked for. */
  onClick: (LayoutCell<Long>, OpenIn) -> Unit,
  modifier: Modifier = Modifier
) {
  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val dots = remember(density) { pileDots(density) }
  // As in [TreemapView]: measuring the names is the one part of drawing that isn't cheap, so it happens
  // when the presentation changes and never on a redraw. There are more of them here than on a treemap,
  // since every row is named rather than one level of them.
  val blocks = remember(presentation, coloring, shading, textMeasurer, density, dots) {
    val colors = CellColors.of(coloring, presentation.cells, shading)
    presentation.cells.map { it.measure(colors, textMeasurer, density, dots) }
  }
  val scrollState = rememberScrollState()
  // A move re-roots the stack, and the new one starts at its own top: the row someone had scrolled down
  // to belonged to the tree they have just left. Resizing keeps the scroll, since the tree is the same.
  LaunchedEffect(presentation.rootNode) {
    scrollState.scrollTo(0)
  }
  // Where the pointer is *in the view*, which is what the card following it is placed by, and null when
  // it is outside. The blocks are a scroll away from that, hence [pointedAt].
  var pointerOffset: Offset? by remember { mutableStateOf(null) }
  // Scrolling moves the blocks under a pointer that hasn't moved, and so does being laid out again, and
  // neither sends a pointer event: without this the panes would keep describing the row that was there.
  LaunchedEffect(presentation, blocks, scrollState.value) {
    onHover(pointerOffset?.let { presentation.pointedAt(it, scrollState.value) })
  }
  Box(modifier) {
    Box(Modifier.fillMaxSize().verticalScroll(scrollState)) {
      Canvas(
        Modifier.fillMaxWidth()
          // As tall as the stack came out, which is what there is to scroll through. The layout works in
          // pixels, like the presentation it laid out.
          .height(with(density) { presentation.layout.contentHeight.toFloat().toDp() })
          // Before the tap handler, as in [TreemapView], so that the block under the pointer is read
          // wherever it is rather than only where a gesture hasn't already claimed the events.
          .pointerInput(presentation, blocks) {
            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent()
                when (event.type) {
                  // A move, and not the enter that comes with it, as in [TreemapView].
                  PointerEventType.Move -> {
                    // The scroll is read here rather than keyed on, so that scrolling doesn't restart
                    // the gesture loop, and it is read live so that a wheel between two moves counts.
                    val offset = event.changes.first().position.inTheView(scrollState.value)
                    pointerOffset = offset
                    onHover(presentation.pointedAt(offset, scrollState.value))
                  }
                  PointerEventType.Exit -> {
                    pointerOffset = null
                    onHover(null)
                  }
                }
              }
            }
          }
          .pointerInput(presentation, blocks) {
            detectOpenPresses { offset, openIn ->
              presentation.cellAt(offset.inTheView(scrollState.value), scrollState.value)
                ?.let { cell -> onClick(cell, openIn) }
            }
          }
      ) {
        // Fills first and outlines after, all of them, as in [TreemapView]: a stroke straddles the edge
        // it is drawn on, so a neighbour's fill would paint over half of a border drawn with it.
        blocks.forEach { block -> drawFill(block) }
        blocks.forEach { block -> drawOutline(block) }
        blocks.forEach { block -> drawLabel(block) }
        // On top of both, so that a neighbour's border doesn't cover the outline of the block the panes
        // are about. The hover goes under the selection, so that the two landing on one block reads as
        // selected.
        if (hovered != selected) {
          blocks.firstOrNull { it.selects == hovered }?.let { block ->
            drawRect(
              color = HOVER_COLOR,
              topLeft = block.topLeft,
              size = block.size,
              style = Stroke(width = HOVER_WIDTH)
            )
          }
        }
        blocks.firstOrNull { it.selects == selected }?.let { block ->
          drawRect(
            color = SELECTION_COLOR,
            topLeft = block.topLeft,
            size = block.size,
            style = Stroke(width = SELECTION_WIDTH)
          )
        }
      }
    }
    // The one shape that has more than it can show, so the one that needs saying so: nothing else in
    // this window scrolls without a pane around it to make that obvious.
    if (scrollState.maxValue > 0) {
      VerticalScrollbar(
        rememberScrollbarAdapter(scrollState),
        Modifier.align(Alignment.CenterEnd).fillMaxHeight()
      )
    }
    NotExpandedBadge(presentation.truncatedNodeCount)
  }
}

/** Where the pointer is in the view, given where it is on the stack: the stack scrolls, the view doesn't. */
private fun Offset.inTheView(scroll: Int): Offset = Offset(x, y - scroll)

private fun StackPresentation.cellAt(
  /** In the view, which is [scroll] pixels down the stack. */
  viewOffset: Offset,
  scroll: Int
): StackCell<Long>? =
  layout.cellAt(TreemapPoint(viewOffset.x.toDouble(), (viewOffset.y + scroll).toDouble()))

/** What the pointer is on, with where it is in the view kept: the card following it needs both. */
private fun StackPresentation.pointedAt(
  viewOffset: Offset,
  scroll: Int
): PointedAt? = cellAt(viewOffset, scroll)?.let { PointedAt(cell = it, offset = viewOffset) }

/** The node across the top, which is what a move changes and what the scroll goes back to the top for. */
private val StackPresentation.rootNode: Long?
  get() = (cells.firstOrNull()?.cell?.subject as? CellSubject.Node)?.node

/** A block with its name and size measured and its colour resolved, so that drawing does no work. */
private class MeasuredBlock(
  val selects: SelectedCell,
  val topLeft: Offset,
  val size: Size,
  val color: Color,
  /** The dots filling a pile of siblings its row had no width for, and null for every other block. */
  val dots: Brush?,
  val borderColor: Color,
  val outline: Stroke,
  val labelColor: Color,
  /** Null when the block is too narrow for a readable name. */
  val label: TextLayoutResult?,
  val labelTopLeft: Offset,
  /**
   * How much the block stands for, and null when the name has taken the row.
   *
   * On the row rather than only in the panes because a stack is read down a chain: a row is where a
   * reader asks how much of the heap is still below them, and every row here is wide enough to be worth
   * asking about.
   */
  val byteSize: TextLayoutResult?,
  val byteSizeTopLeft: Offset
)

private fun PresentedCell<StackCell<Long>>.measure(
  colors: CellColors,
  textMeasurer: TextMeasurer,
  density: Density,
  dots: Brush
): MeasuredBlock {
  val rect = cell.rect
  val padding = with(density) { LABEL_PADDING.toPx() }
  val labelWidth = rect.width - 2 * padding
  val topLeft = Offset(rect.left.toFloat(), rect.top.toFloat())
  val size = Size(rect.width.toFloat(), rect.height.toFloat())
  val measured = if (labelWidth < with(density) { MIN_LABEL_WIDTH.toPx() }) {
    null
  } else {
    textMeasurer.measure(
      text = label,
      style = LABEL_STYLE,
      overflow = TextOverflow.Ellipsis,
      maxLines = 1,
      constraints = Constraints(maxWidth = labelWidth.toInt())
    )
  }
  // The size at the far end of the row, and only where the name has left room for it with a gap: two
  // pieces of text that run into each other read as one word.
  val byteSize = measured?.let {
    val room = labelWidth - it.size.width - with(density) { LABEL_GAP.toPx() }
    textMeasurer.measure(text = formatByteSize(cell.weight), style = LABEL_STYLE, maxLines = 1)
      .takeIf { byteSize -> byteSize.size.width <= room }
  }
  return MeasuredBlock(
    selects = SelectedCell.of(cell.subject),
    topLeft = topLeft,
    size = size,
    color = colors.colorOf(this),
    dots = dots.takeIf { content is CellContent.Leftover },
    borderColor = colors.borderOf(this),
    outline = outlineOf(content),
    labelColor = colors.label,
    label = measured,
    labelTopLeft = Offset(topLeft.x + padding, topLeft.y + centered(measured, size)),
    byteSize = byteSize,
    byteSizeTopLeft = Offset(
      x = topLeft.x + size.width - padding - (byteSize?.size?.width ?: 0),
      y = topLeft.y + centered(byteSize, size)
    )
  )
}

/** Where a line of text goes down a row, which is the middle of it: a row is one line tall. */
private fun centered(
  text: TextLayoutResult?,
  size: Size
): Float = (size.height - (text?.size?.height ?: 0)) / 2

private fun DrawScope.drawFill(block: MeasuredBlock) {
  drawRect(color = block.color, topLeft = block.topLeft, size = block.size)
  // Straight after its own fill, as in [TreemapView]: a pile is where the stack stops, so there is
  // never a row drawn under one for the dots to end up beneath.
  val dots = block.dots ?: return
  drawRect(brush = dots, topLeft = block.topLeft, size = block.size)
}

private fun DrawScope.drawOutline(block: MeasuredBlock) {
  drawRect(
    color = block.borderColor,
    topLeft = block.topLeft,
    size = block.size,
    style = block.outline
  )
}

/**
 * What a block is and how much it holds, on the block itself.
 *
 * No plate under it, unlike the treemap's names: nothing is drawn inside a block here, so the text sits
 * on the fill it was measured against rather than over pictures and outlines it has no say over.
 */
private fun DrawScope.drawLabel(block: MeasuredBlock) {
  val label = block.label ?: return
  drawText(textLayoutResult = label, color = block.labelColor, topLeft = block.labelTopLeft)
  block.byteSize?.let { byteSize ->
    drawText(textLayoutResult = byteSize, color = MUTED_TEXT, topLeft = block.byteSizeTopLeft)
  }
}
