package shark.explorer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.pow
import shark.explorer.CellSubject
import shark.explorer.GraphEdgeCell
import shark.explorer.GraphLayoutResult
import shark.explorer.GraphNodeCell
import shark.explorer.ReachabilityStrength
import shark.explorer.TreemapPoint
import shark.explorer.formatByteSize

/**
 * Draws an already laid out [GraphLayoutResult]: the object the view is rooted at on the left, what it
 * references in the column beside it, and so on rightwards for as far as the reader has expanded.
 *
 * The other three shapes fit the window, so a click on one of them is a move to somewhere else. Here a
 * click *adds to the picture* — it draws what a circle references, or takes them off again — and the
 * picture grows past the window instead, which is why this is the one view that is dragged and zoomed.
 *
 * Everything in it is drawn the way a chain of objects draws it, from [PathStyle]: the same circles with
 * the same letter of the same kind in them, the same purple line, dashed and red where the link may be
 * let go of, the same teal for an object that dominates what hangs off it. A chain and this are one
 * picture in two arrangements, and the two of them agreeing is what makes either worth learning.
 */
@Composable
internal fun GraphView(
  layout: GraphLayoutResult,
  selected: SelectedCell?,
  /** The circle the pointer is on, which is ringed more lightly than the selected one. */
  hovered: SelectedCell?,
  /** The circle the pointer moved onto and where it is, or null when it moved onto none or left. */
  onHover: (PointedAt?) -> Unit,
  /** The circle pressed, which is what expands or collapses it. See [shark.explorer.pressing]. */
  onClick: (GraphNodeCell) -> Unit,
  modifier: Modifier = Modifier
) {
  val textMeasurer = rememberTextMeasurer()
  val density = LocalDensity.current
  // As in every other shape: measuring the names is the one part of drawing that isn't cheap, so it
  // happens when the picture changes and never on a redraw — and dragging one is a redraw a frame.
  val circles = remember(layout, textMeasurer, density) {
    layout.nodes.map { it.measure(textMeasurer, density) }
  }
  val arrows = remember(layout, textMeasurer, density) {
    layout.edges.map { it.measure(textMeasurer, density) }
  }
  val nameColor = MaterialTheme.colorScheme.onSurface
  var viewSize by remember { mutableStateOf(IntSize.Zero) }
  // Null until the reader has moved it, and again whenever the graph is re-rooted: a picture rooted at
  // another object is not one the last drag and zoom mean anything about.
  var moved: GraphTransform? by remember(layout.rootObjectId) { mutableStateOf(null) }
  val rootMargin = with(density) { GRAPH_ROOT_MARGIN.toPx() }

  /**
   * Where the picture is now, read every time rather than held: the gesture handlers below are started
   * once per picture, so a value captured there would be where it was before it was ever dragged — and
   * reading it as it is drawn is what redraws a drag without recomposing anything.
   */
  fun transform() = moved ?: GraphTransform.rootedIn(viewSize, rootMargin)

  Box(
    modifier
      .onSizeChanged { viewSize = it }
      // Before the gesture handlers, as in every other shape, so that the circle under the pointer is
      // read wherever it is rather than only where a gesture hasn't already claimed the events.
      .pointerInput(layout) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            when (event.type) {
              // A move, and not the enter that comes with a pointer arriving, as in [TreemapView].
              PointerEventType.Move -> {
                val offset = event.changes.first().position
                onHover(layout.cellAt(offset, transform())?.let { PointedAt(it, offset) })
              }
              PointerEventType.Exit -> onHover(null)
              PointerEventType.Scroll -> {
                val change = event.changes.first()
                // About the pointer rather than about the middle of the view, which is how anyone
                // aims a zoom: what is under the pointer is what the reader is zooming into.
                moved = transform().zoomedAt(change.position, change.scrollDelta.y)
                change.consume()
              }
            }
          }
        }
      }
      .pointerInput(layout) {
        detectDragGestures { change, dragged ->
          change.consume()
          moved = transform().pannedBy(dragged)
        }
      }
      .pointerInput(layout) {
        // On the tap rather than on the press, unlike the shapes that don't move: a drag across this
        // view starts with a press, and expanding whatever the drag started on is not what it asked for.
        detectTapGestures(onTap = { offset -> layout.cellAt(offset, transform())?.let(onClick) })
      }
  ) {
    Canvas(Modifier.fillMaxSize()) {
      val transform = transform()
      withTransform({
        translate(transform.pan.x, transform.pan.y)
        scale(transform.zoom, transform.zoom, pivot = Offset.Zero)
      }) {
        // Arrows under the circles, so that a line crossing the picture runs behind whatever it passes
        // rather than through the middle of it.
        arrows.forEach { arrow -> drawArrow(arrow, transform.zoom) }
        circles.forEach { circle ->
          drawNode(
            circle = circle,
            role = when {
              circle.selects == selected -> PathRole.TARGET
              // Which is `Dominates ↓` on a step of a chain, and the same colour: this object is why
              // what hangs off it is still in memory.
              circle.cell.dominatesBelow -> PathRole.DOMINATOR
              else -> PathRole.STEP
            },
            isHovered = circle.selects == hovered && hovered != selected,
            nameColor = nameColor,
            measurer = textMeasurer,
            zoom = transform.zoom
          )
        }
      }
    }
  }
}

/** Where the picture sits under the view: dragged to there, and zoomed to there. */
internal data class GraphTransform(
  /** Where the object at the origin of the layout is drawn, in the view's own pixels. */
  val pan: Offset,
  val zoom: Float
) {

  fun pannedBy(dragged: Offset): GraphTransform = copy(pan = pan + dragged)

  /**
   * Zoomed by [scrollDelta] notches of the wheel, keeping whatever is under [pointer] under it.
   *
   * Which is the whole of what makes zooming usable: a zoom about the middle of the view walks the
   * thing being looked at off the edge, and the reader spends the zoom dragging it back.
   */
  fun zoomedAt(
    pointer: Offset,
    scrollDelta: Float
  ): GraphTransform {
    val zoomed = (zoom * ZOOM_PER_NOTCH.pow(-scrollDelta)).coerceIn(MIN_ZOOM, MAX_ZOOM)
    return GraphTransform(
      pan = pointer - (pointer - pan) * (zoomed / zoom),
      zoom = zoomed
    )
  }

  companion object {
    /**
     * Where a picture starts: the object it is rooted at against the left edge, half way down.
     *
     * Half way down because everything is laid out *around* that object rather than below it — what it
     * references runs off in both directions — and against the left edge because everything is laid
     * out to the right of it. See [shark.explorer.GraphLayout].
     */
    fun rootedIn(
      viewSize: IntSize,
      margin: Float
    ) = GraphTransform(pan = Offset(margin, viewSize.height / 2f), zoom = 1f)
  }
}

/** The circle [offset] falls on, given where the picture has been dragged and zoomed to. */
private fun GraphLayoutResult.cellAt(
  offset: Offset,
  transform: GraphTransform
): GraphNodeCell? {
  val inThePicture = (offset - transform.pan) / transform.zoom
  return cellAt(TreemapPoint(inThePicture.x.toDouble(), inThePicture.y.toDouble()))
}

/** A circle with its name measured and what it stands for resolved, so that drawing does no work. */
private class MeasuredCircle(
  val cell: GraphNodeCell,
  val selects: SelectedCell,
  val center: Offset,
  /** What the object is, beside the circle: its class, or how many references were left out. */
  val name: TextLayoutResult,
  /** How much of the heap it holds, under its name, and null for the cell that stands for no object. */
  val retained: TextLayoutResult?
) {

  /** Whether there is more to draw under it, which is what marks a circle as worth pressing. */
  val hasMore: Boolean
    get() {
      // The cell counting what a node had no room for always has: that is the whole of what it is.
      val drawn = cell.drawn ?: return true
      return !cell.isExpanded && drawn.referenceCount > 0
    }
}

private fun GraphNodeCell.measure(
  measurer: TextMeasurer,
  density: Density
): MeasuredCircle {
  val drawn = drawn
  val name = measurer.measure(
    // The class it is, or — for the cell standing for the references its node had no room for, which
    // is no object of the heap dump — how many of them there are.
    text = drawn?.className?.substringAfterLast('.') ?: leftoverLabel(subject),
    style = GRAPH_NAME_STYLE,
    overflow = TextOverflow.Ellipsis,
    maxLines = 1,
    constraints = Constraints(maxWidth = with(density) { GRAPH_LABEL_WIDTH.toPx() }.toInt())
  )
  return MeasuredCircle(
    cell = this,
    selects = SelectedCell.of(subject),
    center = Offset(center.x.toFloat(), center.y.toFloat()),
    name = name,
    retained = drawn?.let {
      measurer.measure(text = formatByteSize(it.retainedSize), style = LABEL_STYLE, maxLines = 1)
    }
  )
}

/** An arrow with the field it is held in measured, and the geometry its head is drawn from. */
private class MeasuredArrow(
  val from: Offset,
  val to: Offset,
  /** Which way it points, as a unit vector, which the head at the far end of it is drawn along. */
  val direction: Offset,
  val strength: ReachabilityStrength,
  /**
   * Whether it points at the circle drawn hanging off this one, or at one drawn elsewhere.
   *
   * The second kind is what says an object is held by more than the one thing the picture hangs it
   * below, which is half of what this shape exists to answer, so it is drawn rather than left out.
   */
  val isSpanning: Boolean,
  /** Whether it is the reference the object at the end of it is still in memory because of. */
  val isDominator: Boolean,
  /** The field it is held in, and null where no field holds it: see [shark.explorer.GraphReference]. */
  val name: TextLayoutResult?
)

private fun GraphEdgeCell.measure(
  measurer: TextMeasurer,
  density: Density
): MeasuredArrow {
  val start = Offset(from.x.toFloat(), from.y.toFloat())
  val end = Offset(to.x.toFloat(), to.y.toFloat())
  val length = hypot(end.x - start.x, end.y - start.y)
  return MeasuredArrow(
    from = start,
    to = end,
    // A reference from an object to itself is never laid out, so there is always a direction to point.
    direction = if (length == 0f) RIGHTWARDS else (end - start) / length,
    strength = strength,
    isSpanning = isSpanning,
    isDominator = reference.isDominator,
    name = reference.reference?.let {
      measurer.measure(
        // The field alone, without the class it is declared on: on an arrow, the class is the circle
        // the arrow leaves. See [referenceName].
        text = buildAnnotatedString { append(referenceName(it)) },
        style = LABEL_STYLE,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = with(density) { GRAPH_COLUMN_WIDTH.toPx() }.toInt())
      )
    }
  )
}

/**
 * One arrow: a line from the edge of one circle to the edge of the next, with the field it is held in
 * written along it.
 *
 * The name is left off below [MIN_LABEL_ZOOM], where it would be unreadable anyway and where there are
 * enough circles on screen for every name to be crossing another one.
 */
private fun DrawScope.drawArrow(
  arrow: MeasuredArrow,
  zoom: Float
) {
  val radius = NODE_RADIUS.toPx()
  val color = if (arrow.isDominator && arrow.strength == ReachabilityStrength.STRONG) {
    // The teal a chain rings a dominator in, on the line rather than around the circle: whether an
    // object is dominated depends on which reference reached it, so only an arrow can say it.
    DOMINATOR_COLOR
  } else {
    connectorColor(arrow.strength)
  }
  val start = arrow.from + arrow.direction * radius
  val end = arrow.to - arrow.direction * (radius + ARROW_HEIGHT.toPx())
  drawLine(
    color = color,
    start = start,
    end = end,
    strokeWidth = CONNECTOR_WIDTH.toPx(),
    pathEffect = dashOrNull(arrow.strength) ?: spanningDashOrNull(arrow.isSpanning)
  )
  drawArrowHead(arrow.to - arrow.direction * radius, color, arrow.direction)
  val name = arrow.name
  if (name == null || zoom < MIN_LABEL_ZOOM) {
    return
  }
  // Half way along and on a plate, because an arrow crossing the picture runs over whatever is between
  // its two ends — the same reason a name on the treemap sits on one.
  val middle = (start + end) / 2f
  val topLeft = Offset(
    x = middle.x - name.size.width / 2f,
    y = middle.y - name.size.height - LABEL_PLATE_PADDING
  )
  drawRect(
    color = LABEL_PLATE_COLOR,
    topLeft = topLeft - Offset(LABEL_PLATE_PADDING, LABEL_PLATE_PADDING),
    size = Size(
      width = name.size.width + 2 * LABEL_PLATE_PADDING,
      height = name.size.height + 2 * LABEL_PLATE_PADDING
    )
  )
  drawText(textLayoutResult = name, color = MUTED_TEXT, topLeft = topLeft)
}

/** One circle, with what the object is beside it and how much of the heap it holds under that. */
private fun DrawScope.drawNode(
  circle: MeasuredCircle,
  role: PathRole,
  isHovered: Boolean,
  nameColor: Color,
  measurer: TextMeasurer,
  zoom: Float
) {
  val radius = NODE_RADIUS.toPx()
  drawObjectCircle(
    center = circle.center,
    kind = circle.cell.drawn?.kind,
    role = role,
    radius = radius,
    measurer = measurer
  )
  // Outside the ring the role already drew, so that a hovered dominator reads as both.
  if (isHovered) {
    drawNodeRing(circle.center, radius, HOVER_COLOR, HOVER_RING_GAP.toPx())
  }
  // A triangle beside a node with references nobody has drawn yet, the way a tree draws the handle
  // that opens a row — which is the one thing a picture expanded a click at a time has to say: where
  // there is more to click. Before the names are given up on, since that is when it matters most.
  val markerTip = circle.center.x + radius + MORE_MARKER_GAP.toPx() + ARROW_HEIGHT.toPx()
  if (circle.hasMore) {
    drawArrowHead(Offset(markerTip, circle.center.y), CONNECTOR_COLOR, RIGHTWARDS)
  }
  if (zoom < MIN_LABEL_ZOOM) {
    return
  }
  // Past where that triangle goes whether or not there is one, so that the names of one column line up.
  val left = markerTip + NAME_GAP.toPx()
  drawText(
    textLayoutResult = circle.name,
    color = nameColor,
    topLeft = Offset(left, circle.center.y - circle.name.size.height)
  )
  circle.retained?.let { retained ->
    drawText(textLayoutResult = retained, color = MUTED_TEXT, topLeft = Offset(left, circle.center.y))
  }
}

/**
 * What the cell standing for the references a node had no room for says: how many of them there are.
 *
 * Pressing it draws another page rather than all of them, because a node holding thousands is exactly
 * the node whose references nobody wants drawn at once. See [shark.explorer.ObjectGraph].
 */
private fun leftoverLabel(subject: CellSubject<Long>): String {
  val count = (subject as? CellSubject.Group)?.nodeCount ?: 0
  return if (count == 1) "1 more reference" else "$count more references"
}

/**
 * How an arrow to a circle drawn elsewhere is told from one to the circle hanging off it: dashed, where
 * the strength of the link hasn't already dashed it.
 *
 * Sparser than the dashes of a link the collector may let go of, and for the same reason the dots above
 * a cut chain are: this says how the picture was drawn rather than how the object is held.
 */
private fun DrawScope.spanningDashOrNull(isSpanning: Boolean): PathEffect? =
  if (isSpanning) {
    null
  } else {
    PathEffect.dashPathEffect(floatArrayOf(SPANNING_DASH_ON.toPx(), SPANNING_DASH_OFF.toPx()))
  }

/** Where an arrow points when its two ends are the same point, which nothing laid out ever is. */
private val RIGHTWARDS = Offset(1f, 0f)

/** How far the object the picture is rooted at sits from the left edge when it opens. */
private val GRAPH_ROOT_MARGIN = 48.dp

/** Between a circle and the name beside it, which is what keeps the two reading as two things. */
private val NAME_GAP = 6.dp

/** And between the circle and the triangle saying there is more under it. */
private val MORE_MARKER_GAP = 4.dp

/** Far enough out to clear the ring a dominator or the selection already drew. */
private val HOVER_RING_GAP = 5.5.dp

private val SPANNING_DASH_ON = 6.dp
private val SPANNING_DASH_OFF = 4.dp

/** What a circle's name is written in: a label, in the weight the rest of the window names objects in. */
private val GRAPH_NAME_STYLE = LABEL_STYLE.copy(fontWeight = FontWeight.Bold)

/**
 * How far one notch of the wheel zooms, and how far in and out it goes at all.
 *
 * Out to where a few hundred circles fit the window, which is a picture read for its shape rather than
 * for its names, and in to twice size, which is what looking closely at one corner of it wants.
 */
private const val ZOOM_PER_NOTCH = 1.1f
private const val MIN_ZOOM = 0.15f
private const val MAX_ZOOM = 2.5f

/** Below which nothing is named: the text would be unreadable, and there is a great deal of it. */
private const val MIN_LABEL_ZOOM = 0.55f
