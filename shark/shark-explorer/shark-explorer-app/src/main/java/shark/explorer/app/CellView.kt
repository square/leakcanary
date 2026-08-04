package shark.explorer.app

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import shark.explorer.CellContent
import shark.explorer.CellSubject
import shark.explorer.HeapDominatorTreemap
import shark.explorer.LayoutCell
import shark.explorer.ObjectGroupKind
import shark.explorer.ReverseDominatorTree
import shark.explorer.ReverseNodeKind
import shark.explorer.TreemapPoint

/** Which shape a heap dump's domination is drawn as. Pick one above the view. */
internal enum class ViewShape(val displayName: String) {

  /** Nested rectangles: area is retained size, nesting is domination. */
  TREEMAP("Treemap"),

  /**
   * Rings around a centre, the way DaisyDisk draws a disk: one ring per level, and a sector's sweep is
   * its share of what the ring inside it retains. Nesting reads as distance from the middle, which
   * says more about the shape of the tree than a treemap does and less about exact sizes.
   */
  RADIAL("Radial"),

  /**
   * A row per level, roots at the top, the way a profiler draws a call tree upside down: a block's
   * width is its share of the heap and its depth is how far down the screen it is. The one shape that
   * doesn't spend area on nesting, so the deep end of a chain is drawn and named at full size — and
   * therefore the one shape taller than the window, which is why it scrolls.
   */
  STACK("Stack"),

  /**
   * The same stack of rows the other way up, of the other of the heap dump's two trees: every object of
   * the dump on the row of its class along the bottom, and what dominates them stacked above, class by
   * class. See [shark.explorer.ReverseDominatorTree].
   *
   * So a row here is a pile of objects rather than one object, and reading up a column answers "what
   * holds all the `byte[]`, and what holds that" — which [STACK] can only answer one object at a time.
   */
  CLASSES("Classes");

  /**
   * Whether this shape draws [nodeId] at all, which is what a shape being switched leaves behind.
   *
   * The heap dump's two trees share their root and no other node — see [shark.explorer.HeapTree] — so a
   * path zoomed into one of them is nothing to the other, and which shape is drawn is what says which of
   * the two a node is expected to be in.
   */
  fun draws(nodeId: Long): Boolean = nodeId == HeapDominatorTreemap.ROOT_OBJECT_ID ||
    ReverseDominatorTree.isReverseNode(nodeId) == (this == CLASSES)
}

/**
 * Which cell is selected, in terms that outlive a relayout.
 *
 * An object's own id for a cell that is a node; the parent's id for the cell standing for the children
 * it didn't draw, since two groups never share a parent. Resizing the window, switching shape and
 * zooming all lay the view out again, and the selection has to survive that.
 */
internal data class SelectedCell(
  val objectId: Long,
  val isGroup: Boolean
) {
  companion object {
    fun of(subject: CellSubject<Long>): SelectedCell = when (subject) {
      is CellSubject.Node -> SelectedCell(subject.node, isGroup = false)
      is CellSubject.Group -> SelectedCell(subject.parent, isGroup = true)
      // The same selection as the object it's nested in, so that clicking either outlines both.
      is CellSubject.Own -> SelectedCell(subject.node, isGroup = false)
    }
  }
}

/**
 * A cell the pointer moved onto, and where in the view the pointer was.
 *
 * The position comes along with the cell because the card naming what's under the pointer is placed by it —
 * see [PointerCard] — and it is in the view's own coordinates, which is what that card is positioned in.
 */
internal data class PointedAt(
  val cell: LayoutCell<Long>,
  val offset: Offset
)

/** Says when a view is showing less detail than it had room for, rather than truncating silently. */
@Composable
internal fun BoxScope.NotExpandedBadge(nodeCount: Int) {
  if (nodeCount == 0) {
    return
  }
  Surface(
    Modifier.align(Alignment.BottomEnd).padding(8.dp),
    color = MaterialTheme.colorScheme.surfaceVariant
  ) {
    Text(
      if (nodeCount == 1) "1 node not expanded" else "$nodeCount nodes not expanded",
      Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      style = MaterialTheme.typography.labelSmall
    )
  }
}

internal fun Offset.toTreemapPoint() = TreemapPoint(x.toDouble(), y.toDouble())

/**
 * How a cell is outlined: dashed for every instance of one class and for the objects nothing in
 * particular holds, dotted for the siblings that didn't fit, solid for an object, for the two halves of
 * the heap dump and for a row of the classes view.
 *
 * A pile of objects drawn among objects shouldn't have the same edge as one object. Along with the washed
 * out fill and the label, it's the third thing saying this cell isn't something you can inspect the
 * fields of. Every cell of the classes view is a pile, so there a dashed edge would mark nothing out and
 * make the rows hard to tell apart: see [CellContent.ObjectRow].
 */
internal fun outlineOf(content: CellContent): Stroke = when {
  content is CellContent.ObjectGroup && content.kind == ObjectGroupKind.CLASS -> Stroke(
    width = PILE_BORDER_WIDTH,
    pathEffect = PathEffect.dashPathEffect(CLASS_GROUP_DASH_INTERVALS)
  )
  // The one row of that view that isn't objects gathered by something: they have nothing in common but
  // that nothing in particular holds them, so the edge says so the way a pile's does.
  content is CellContent.ObjectRow && content.kind == ReverseNodeKind.NO_OWNER -> Stroke(
    width = PILE_BORDER_WIDTH,
    pathEffect = PathEffect.dashPathEffect(CLASS_GROUP_DASH_INTERVALS)
  )
  content is CellContent.Leftover -> Stroke(
    width = PILE_BORDER_WIDTH,
    pathEffect = PathEffect.dashPathEffect(LEFTOVER_DOT_INTERVALS)
  )
  else -> Stroke(width = BORDER_WIDTH)
}

/**
 * What the siblings a rectangle had no room for are filled with: dots, over the flat colour every other
 * cell gets.
 *
 * A pile of them can be a good part of the view, and one flat block that size reads as one enormous object
 * — a bitmap, usually, since that is the only thing that ever is one. A texture says "many small things"
 * before the label is read, and being an even texture over the whole rectangle rather than a drawing of
 * each of them keeps the pile looking like the one thing a click can land on.
 *
 * One repeated tile rather than a circle per dot, because the whole map is redrawn every time the pointer
 * moves onto another rectangle, and a pile that fills the view is tens of thousands of dots.
 */
internal fun pileDots(density: Density): Brush {
  val side = with(density) { PILE_DOT_SPACING.toPx() }.roundToInt().coerceAtLeast(1)
  val tile = ImageBitmap(side, side)
  CanvasDrawScope().draw(
    density = density,
    layoutDirection = LayoutDirection.Ltr,
    canvas = Canvas(tile),
    size = Size(side.toFloat(), side.toFloat())
  ) {
    drawCircle(
      color = PILE_DOT_COLOR,
      radius = with(density) { PILE_DOT_RADIUS.toPx() },
      center = Offset(side / 2f, side / 2f)
    )
  }
  return ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
}

/**
 * The layout thresholds in dp. The layouts work in pixels, so they have to be scaled or a cell that's
 * big enough to subdivide on one display is too small on another.
 */
internal val MIN_SUBDIVIDE_WIDTH = 12.dp
internal val MIN_SUBDIVIDE_HEIGHT = 12.dp
internal val MIN_DRAW_SIZE = 3.dp

/**
 * How much of a container's outline counts as the container rather than as what's inside it.
 *
 * A subdivided rectangle is covered by its own contents, so its border is the only part of it left to
 * click, and a 1 px line is not something a pointer can be expected to hit.
 */
internal val EDGE_GRAB = 4.dp
internal val MIN_SUBDIVIDE_ARC_LENGTH = 40.dp
internal val MIN_DRAW_ARC_LENGTH = 3.dp

/**
 * How tall one row of the stack is: a line of [LABEL_STYLE] with [LABEL_PADDING] above and below it,
 * since a row holds a name and nothing else.
 */
internal val STACK_ROW_HEIGHT = 18.dp

/**
 * And how wide a block has to be for the row under it to say anything, and to be drawn at all.
 *
 * Both smaller than the treemap's floors, because a level of a stack costs no width: a block half as
 * wide as another still gets a full row for its children, so subdividing further is worth it for longer
 * than it is in a picture where nesting eats area.
 */
internal val MIN_SUBDIVIDE_STACK_WIDTH = 6.dp
internal val MIN_DRAW_STACK_WIDTH = 2.dp

internal val LABEL_PADDING = 3.dp
internal val MIN_LABEL_WIDTH = 24.dp
internal val MIN_LABEL_HEIGHT = 13.dp

/** How far apart two pieces of text on one cell have to be to read as two. See [StackView]. */
internal val LABEL_GAP = 8.dp
internal const val BORDER_WIDTH = 1f
internal const val SELECTION_WIDTH = 3f

/**
 * How deep the children of the node the view is rooted at are, which are the ones the map names and marks
 * off from each other. The root itself fills the viewport, so its own outline is the view's edge.
 */
internal const val ROOT_CHILD_DEPTH = 1

/** Heavier than a selection's outline, because it says what the whole map is divided into. */
internal const val ROOT_CHILD_BORDER_WIDTH = 4f

/** Near black rather than the fill's own border colour, so that the division reads at a glance. */
internal val ROOT_CHILD_BORDER_COLOR = Color(0xFF1A1A1A)

/**
 * What a name on the map is drawn on: light enough to read solid text against, see through enough to leave
 * the rectangles and bitmaps under it visible.
 */
internal val LABEL_PLATE_COLOR = Color(0xB8FFFFFF)

internal const val LABEL_PLATE_PADDING = 2f

/**
 * Thinner than the selection's outline, because the pointer is already saying where it is: this only has
 * to say which rectangle the panels beside the view are describing, and a second heavy outline following
 * the mouse around reads as a second selection.
 */
internal const val HOVER_WIDTH = 2f
internal const val PILE_BORDER_WIDTH = 2f
internal val CLASS_GROUP_DASH_INTERVALS = floatArrayOf(5f, 4f)
internal val LEFTOVER_DOT_INTERVALS = floatArrayOf(2f, 3f)

internal val LABEL_STYLE = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)

/**
 * How far apart the dots of [pileDots] are, and how big. Far enough apart to read as dots at a glance and
 * small enough that the colour under them still says how firmly the pile is held.
 */
private val PILE_DOT_SPACING = 7.dp
private val PILE_DOT_RADIUS = 1.1.dp

/** The same near white as a name's plate, and for the same reason: it lightens without recolouring. */
private val PILE_DOT_COLOR = Color(0x8CFFFFFF)
