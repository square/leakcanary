package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import shark.explorer.HeapObjectSummary
import shark.explorer.ObjectGroupSummary
import shark.explorer.ReachabilityStrength
import shark.explorer.ReverseNodeSummary
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

/**
 * What the pointer is on, in a card that follows it around the view.
 *
 * Beside the pointer rather than in a pane, because this is the one thing the reader is asking as they sweep
 * across the map — what is this rectangle — and answering it at the edge of the window makes them look away
 * from the thing they're pointing at. The chain holding it is the slower question, and that is drawn onto the
 * end of the chain in the pane: see [RootPathPanel].
 *
 * A rectangle that isn't one object gets the card too, and needs it most: a pile is named on the map by a
 * count and a simple class name, and the fully qualified name saying which class that is fits nowhere else.
 * See [Selection].
 *
 * [placeCard] keeps it clear of the pointer and inside the view. Nothing here is clickable — the pointer is
 * on the map, and it leaving the map is what closes this.
 */
@Composable
internal fun PointerCard(
  selection: Selection,
  coloring: CellColoring,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier.width(POINTER_CARD_WIDTH),
    color = POINTER_CARD_COLOR,
    shape = RoundedCornerShape(POINTER_CARD_CORNER),
    shadowElevation = POINTER_CARD_ELEVATION
  ) {
    Column(
      Modifier.padding(POINTER_CARD_PADDING),
      verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
      when (selection) {
        is Selection.Object -> ObjectLines(selection.summary, coloring)
        is Selection.ObjectGroup -> ObjectGroupLines(selection.summary, coloring)
        is Selection.Row -> RowLines(selection.summary, coloring)
        is Selection.Group -> GroupLines(selection)
      }
    }
  }
}

@Composable
private fun ObjectLines(
  summary: HeapObjectSummary,
  coloring: CellColoring
) {
  // The same three lines a step of a chain names an object with, so that the card and the chain beside
  // the map read as one answer rather than as two ways of saying which object this is.
  ObjectIdentity(
    className = summary.className,
    typeName = summary.kind?.typeName,
    objectId = summary.objectId
  )
  summary.headline?.let { headline ->
    Text(headline, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
  }
  StrengthLine(summary.strength, coloring)
  // The same numbers the details panel gives a labelled row each, on two lines: a card that follows the
  // pointer has to be read at a glance, and it covers the map for as long as it's up.
  Text(summary.retainedText(), style = MaterialTheme.typography.bodySmall)
  Text(summary.shallowText(), style = MaterialTheme.typography.bodySmall)
}

/**
 * Every instance of one class under the root, or the uncollected garbage.
 *
 * The fully qualified class name is the line the map can't draw: a rectangle has room for `42 × Bitmap`
 * and no more, and which `Bitmap` that is, is the whole question a pile of them raises.
 */
@Composable
private fun ObjectGroupLines(
  summary: ObjectGroupSummary,
  coloring: CellColoring
) {
  Text(summary.title(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
  summary.className?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  StrengthLine(summary.strength, coloring)
  Text(summary.retainedText(), style = MaterialTheme.typography.bodySmall)
  Text(PILE_OF_OBJECTS, style = MaterialTheme.typography.bodySmall)
}

/**
 * One row of the classes view, which is the pile a reader points at most: the row is named by a count and
 * a simple class name, and which class that is, is the whole question it raises.
 *
 * How much of the heap it accounts for rather than what it retains, which is what its width is: see
 * [shark.explorer.ReverseNodeSummary.byteCount].
 */
@Composable
private fun RowLines(
  summary: ReverseNodeSummary,
  coloring: CellColoring
) {
  Text(summary.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
  summary.className?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  StrengthLine(summary.strength, coloring)
  Text(summary.accountsForText(), style = MaterialTheme.typography.bodySmall)
  Text(ROW_OF_OBJECTS, style = MaterialTheme.typography.bodySmall)
}

/** The children of a rectangle that its subdivision had no room for. See [shark.explorer.CellSubject.Group]. */
@Composable
private fun GroupLines(selection: Selection.Group) {
  Text(
    selection.title(),
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.Bold
  )
  // Which cell they were left out of, since they have nothing else in common: that is where to go if any
  // of them is worth finding.
  Text("Held by ${selection.parentLabel}", style = MaterialTheme.typography.bodySmall)
  Text(
    "${selection.byteCountName} ${formatByteSize(selection.byteCount)}",
    style = MaterialTheme.typography.bodySmall
  )
  Text(
    if (selection.isRows) LEFTOVER_ROWS else LEFTOVER_OBJECTS,
    style = MaterialTheme.typography.bodySmall
  )
}

/** How firmly what the pointer is on is held, beside the colour the map drew it in. */
@Composable
private fun StrengthLine(
  strength: ReachabilityStrength,
  coloring: CellColoring
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(Modifier.size(SWATCH_SIZE).background(legendColor(coloring, strength)))
    Text(strength.reachabilityText, style = MaterialTheme.typography.bodySmall)
  }
}

private fun HeapObjectSummary.retainedText(): String =
  "Retains ${formatByteSize(retainedSize)} in ${formatObjectCount(retainedCount)}"

private fun HeapObjectSummary.shallowText(): String =
  "${formatByteSize(shallowSize)} of its own, dominates ${formatObjectCount(dominatedObjectCount)}"

private fun ObjectGroupSummary.retainedText(): String =
  "Retains ${formatByteSize(retainedSize)} in ${formatObjectCount(objectCount)}"

/** How much of the heap a row is as wide as, which is not what the objects on it retain. */
private fun ReverseNodeSummary.accountsForText(): String = "$ACCOUNTS_FOR ${formatByteSize(byteCount)}"

/**
 * What a pile of objects has to say for itself in one line, because a rectangle full of them looks exactly
 * like one object until something says otherwise. The details panel spells out which kind of pile it is.
 *
 * A pile of one class is a node of the tree, so clicking it goes into it and the objects are there. See
 * [LEFTOVER_OBJECTS] for the pile that isn't.
 */
internal const val PILE_OF_OBJECTS = "Not one object. Click it to reach the objects it stands for."

/**
 * The same for a row of the classes view, where clicking leads to the rows above rather than to objects:
 * the objects a row gathers are of one class, and every class is a row of that view already.
 */
internal const val ROW_OF_OBJECTS =
  "Not one object. Click it to give what dominates these the whole width."

/**
 * The other kind of pile: what a rectangle's subdivision had no room for. It is no node of the tree, so
 * there is nothing to go into and the promise [PILE_OF_OBJECTS] makes would be a lie here. What a click
 * does instead is root the map at the rectangle they were left out of, which is where there is the room
 * to draw them one by one. See `TreemapLayout.maxRootChildren`.
 */
internal const val LEFTOVER_OBJECTS =
  "Not one object. Click it for what holds them, where there is room to draw them one by one."

/**
 * And the classes left off a row of the classes view, where a click leads the other way: the row they
 * belong to is above the one they were left out of, so what gives them the width is rooting the view at
 * the row below them. See [DetailsPanel]'s `ROW_GROUP_EXPLANATION`.
 */
internal const val LEFTOVER_ROWS =
  "Not one class. Click it to root the view at the row below, where there is room to draw them one by one."

/**
 * Where a card of [cardSize] goes for a pointer at [pointer] in a view of [viewSize]: below and to the right
 * of the pointer by [gap], and on the other side of it instead when that would run past the view's edge.
 *
 * Never under the pointer, which is the one placement that would break it: the card is a [Surface], and a
 * Material surface swallows pointer events, so a card the pointer ends up inside is a card that takes the
 * hover away from the map, closes itself, and starts over. Hence the gap, and hence flipping to the other
 * side rather than sliding along the edge.
 *
 * Clamped to the view as a last resort, for a card that fits nowhere beside the pointer — a window narrower
 * than the card. Nothing outside the view is a place the window has, so a clamped card is still readable.
 */
internal fun placeCard(
  pointer: Offset,
  cardSize: IntSize,
  viewSize: IntSize,
  gap: Float
): IntOffset {
  val x = beside(
    pointer = pointer.x,
    cardLength = cardSize.width,
    viewLength = viewSize.width,
    gap = gap
  )
  val y = beside(
    pointer = pointer.y,
    cardLength = cardSize.height,
    viewLength = viewSize.height,
    gap = gap
  )
  return IntOffset(x, y)
}

/** One axis of [placeCard]: after the pointer, before it when there is no room after, clamped if neither. */
private fun beside(
  pointer: Float,
  cardLength: Int,
  viewLength: Int,
  gap: Float
): Int {
  val after = pointer + gap
  if (after + cardLength <= viewLength) {
    return after.toInt()
  }
  val before = pointer - gap - cardLength
  if (before >= 0f) {
    return before.toInt()
  }
  return (viewLength - cardLength).coerceAtLeast(0)
}

/** How far from the pointer the card sits, which is enough that the pointer never lands on it. */
internal val POINTER_CARD_GAP = 18.dp

/** As wide as a class name and what a step says about the object, and no wider: it covers the map. */
private val POINTER_CARD_WIDTH = 280.dp

private val POINTER_CARD_PADDING = 8.dp
private val POINTER_CARD_CORNER = 8.dp

/** High enough to read as sitting above the map rather than being drawn into it. */
private val POINTER_CARD_ELEVATION = 12.dp

/** Near white rather than the theme's surface, so that it separates from a rectangle of any colour. */
private val POINTER_CARD_COLOR = Color(0xFFFCFCFD)
