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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import shark.explorer.CellContent
import shark.explorer.CellSubject
import shark.explorer.LayoutCell
import shark.explorer.ObjectGroupKind
import shark.explorer.TreemapPoint

/** Which shape the dominator tree is drawn as. Pick one above the view. */
internal enum class ViewShape(val displayName: String) {

  /** Nested rectangles: area is retained size, nesting is domination. */
  TREEMAP("Treemap"),

  /**
   * Rings around a centre, the way DaisyDisk draws a disk: one ring per level, and a sector's sweep is
   * its share of what the ring inside it retains. Nesting reads as distance from the middle, which
   * says more about the shape of the tree than a treemap does and less about exact sizes.
   */
  RADIAL("Radial")
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
 * How a cell is outlined: dashed for every instance of one class, dotted for the siblings that didn't
 * fit, solid for an object and for the two halves of the heap dump.
 *
 * A pile of objects shouldn't have the same edge as one object, in either shape. Along with the washed
 * out fill and the label, it's the third thing saying this cell isn't something you can inspect the
 * fields of.
 */
internal fun outlineOf(content: CellContent): Stroke = when {
  content is CellContent.ObjectGroup && content.kind == ObjectGroupKind.CLASS -> Stroke(
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

internal val LABEL_PADDING = 3.dp
internal val MIN_LABEL_WIDTH = 24.dp
internal val MIN_LABEL_HEIGHT = 13.dp
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
