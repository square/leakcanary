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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import shark.explorer.TreemapLayout
import shark.explorer.TreemapLayoutResult
import shark.explorer.TreemapPoint
import shark.explorer.TreemapRect
import shark.explorer.TreemapTree

/**
 * Draws [tree] rooted at [root] as a treemap, filling the available space.
 *
 * A press selects the rectangle under the pointer and a double click zooms into it. Everything is
 * drawn into a single [Canvas], so there are no per-rectangle composables: see this module's
 * `AGENTS.md` for what that means for tests.
 */
@Composable
fun TreemapView(
  tree: TreemapTree<Long>,
  root: Long,
  labelOf: (Long) -> String,
  selected: Long?,
  onSelect: (Long) -> Unit,
  onZoomInto: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  var viewportSize by remember { mutableStateOf(IntSize.Zero) }
  val textMeasurer = rememberTextMeasurer()
  // TreemapLayout works in pixels, so its thresholds have to be scaled or a rectangle that's big
  // enough to subdivide on one display is too small on another.
  val density = LocalDensity.current
  val layout = remember(density) {
    with(density) {
      TreemapLayout<Long>(
        minSubdivideWidth = MIN_SUBDIVIDE_WIDTH.toPx().toDouble(),
        minSubdivideHeight = MIN_SUBDIVIDE_HEIGHT.toPx().toDouble(),
        minDrawSize = MIN_DRAW_SIZE.toPx().toDouble(),
        headerHeight = HEADER_HEIGHT.toPx().toDouble()
      )
    }
  }
  // Laying out a dominator tree reads the heap dump for every visible label, so this must happen
  // when the tree, the root or the size change, and never on a redraw.
  val rendering = remember(layout, tree, root, viewportSize, textMeasurer) {
    renderTreemap(layout, tree, root, labelOf, viewportSize, textMeasurer, density)
  }

  Box(modifier.onSizeChanged { viewportSize = it }) {
    Canvas(
      Modifier.fillMaxSize()
        .pointerInput(rendering) {
          detectTapGestures(
            // On press rather than on tap: with a double click handler installed, a tap is only
            // reported once the double click window has passed, which makes selection feel stuck.
            onPress = { offset -> rendering.nodeAt(offset)?.let(onSelect) },
            onDoubleTap = { offset -> rendering.nodeAt(offset)?.let(onZoomInto) }
          )
        }
    ) {
      rendering.cells.forEach { cell ->
        drawCell(cell, isSelected = cell.node == selected)
      }
    }
    if (rendering.truncatedNodeCount > 0) {
      Surface(
        Modifier.align(Alignment.BottomEnd).padding(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
      ) {
        Text(
          "${rendering.truncatedNodeCount} nodes not expanded",
          Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          style = MaterialTheme.typography.labelSmall
        )
      }
    }
  }
}

/** A laid out treemap, with its labels already measured so that drawing does no work. */
private class TreemapRendering(
  val cells: List<RenderedCell>,
  val truncatedNodeCount: Int,
  private val layout: TreemapLayoutResult<Long>?
) {
  fun nodeAt(offset: Offset): Long? =
    layout?.cellAt(TreemapPoint(offset.x.toDouble(), offset.y.toDouble()))?.node
}

private class RenderedCell(
  val node: Long,
  val topLeft: Offset,
  val size: Size,
  val depth: Int,
  val labelOffset: Offset,
  /** Null when the rectangle is too small for a readable label. */
  val label: TextLayoutResult?
)

@Suppress("LongParameterList")
private fun renderTreemap(
  layout: TreemapLayout<Long>,
  tree: TreemapTree<Long>,
  root: Long,
  labelOf: (Long) -> String,
  viewportSize: IntSize,
  textMeasurer: TextMeasurer,
  density: Density
): TreemapRendering {
  if (viewportSize.width == 0 || viewportSize.height == 0) {
    return TreemapRendering(emptyList(), truncatedNodeCount = 0, layout = null)
  }
  val viewport = TreemapRect(
    left = 0.0,
    top = 0.0,
    right = viewportSize.width.toDouble(),
    bottom = viewportSize.height.toDouble()
  )
  val result = layout.layout(tree, viewport, root)
  val labelPadding = with(density) { LABEL_PADDING.toPx() }
  val minLabelWidth = with(density) { MIN_LABEL_WIDTH.toPx() }
  val minLabelHeight = with(density) { MIN_LABEL_HEIGHT.toPx() }
  val cells = result.cells.map { cell ->
    val rect = cell.rect
    val labelWidth = rect.width - 2 * labelPadding
    RenderedCell(
      node = cell.node,
      topLeft = Offset(rect.left.toFloat(), rect.top.toFloat()),
      size = Size(rect.width.toFloat(), rect.height.toFloat()),
      depth = cell.depth,
      labelOffset = Offset(labelPadding, labelPadding),
      label = if (labelWidth >= minLabelWidth && rect.height >= minLabelHeight) {
        textMeasurer.measure(
          text = labelOf(cell.node),
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
  return TreemapRendering(cells, result.truncatedNodeCount, result)
}

private fun DrawScope.drawCell(
  cell: RenderedCell,
  isSelected: Boolean
) {
  drawRect(color = cellColor(cell.depth), topLeft = cell.topLeft, size = cell.size)
  drawRect(
    color = BORDER_COLOR,
    topLeft = cell.topLeft,
    size = cell.size,
    style = Stroke(width = BORDER_WIDTH)
  )
  if (isSelected) {
    drawRect(
      color = SELECTION_COLOR,
      topLeft = cell.topLeft,
      size = cell.size,
      style = Stroke(width = SELECTION_WIDTH)
    )
  }
  cell.label?.let { label ->
    drawText(
      textLayoutResult = label,
      color = LABEL_COLOR,
      topLeft = cell.topLeft + cell.labelOffset
    )
  }
}

/**
 * Rotates the hue and darkens as nesting deepens, so that a rectangle's depth is readable at a
 * glance. Cycles rather than indexing a palette, because the layout puts no bound on depth.
 */
private fun cellColor(depth: Int): Color = Color.hsv(
  hue = (BASE_HUE + depth * HUE_STEP) % 360f,
  saturation = 0.28f,
  value = (0.98f - depth * 0.05f).coerceAtLeast(0.55f)
)

private const val BASE_HUE = 205f
private const val HUE_STEP = 47f
private val MIN_SUBDIVIDE_WIDTH = 40.dp
private val MIN_SUBDIVIDE_HEIGHT = 24.dp
private val MIN_DRAW_SIZE = 3.dp
private val HEADER_HEIGHT = 18.dp
private val LABEL_PADDING = 3.dp
private val MIN_LABEL_WIDTH = 24.dp
private val MIN_LABEL_HEIGHT = 13.dp
private const val BORDER_WIDTH = 1f
private const val SELECTION_WIDTH = 3f
private val BORDER_COLOR = Color(0x33000000)
private val SELECTION_COLOR = Color(0xFF0B57D0)
private val LABEL_COLOR = Color(0xFF1B1B1B)
private val LABEL_STYLE = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
