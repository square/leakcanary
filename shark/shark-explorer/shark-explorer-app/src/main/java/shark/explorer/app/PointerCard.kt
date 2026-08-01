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
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectSummary
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

/**
 * Which object the pointer is on, in a card that follows it around the view.
 *
 * Beside the pointer rather than in a pane, because this is the one thing the reader is asking as they sweep
 * across the map — what is this rectangle — and answering it at the edge of the window makes them look away
 * from the thing they're pointing at. The chain holding it is the slower question, and that stays in the
 * pane: see [HoveredPathPanel].
 *
 * [placeCard] keeps it clear of the pointer and inside the view. Nothing here is clickable — the pointer is
 * on the map, and it leaving the map is what closes this.
 */
@Composable
internal fun PointerCard(
  summary: HeapObjectSummary,
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
      Text(summary.label, style = MaterialTheme.typography.titleSmall)
      Text(summary.className, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
      if (summary.objectId != HeapDominatorTreemap.ROOT_OBJECT_ID) {
        Text(objectIdText(summary.objectId), style = MaterialTheme.typography.bodySmall)
      }
      summary.headline?.let { headline ->
        Text(headline, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
      }
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(Modifier.size(SWATCH_SIZE).background(legendColor(coloring, summary.strength)))
        Text(summary.strength.reachabilityText, style = MaterialTheme.typography.bodySmall)
      }
      // The same numbers the details panel gives a labelled row each, on two lines: a card that follows the
      // pointer has to be read at a glance, and it covers the map for as long as it's up.
      Text(summary.retainedText(), style = MaterialTheme.typography.bodySmall)
      Text(summary.shallowText(), style = MaterialTheme.typography.bodySmall)
    }
  }
}

private fun HeapObjectSummary.retainedText(): String =
  "Retains ${formatByteSize(retainedSize)} in ${formatObjectCount(retainedCount)}"

private fun HeapObjectSummary.shallowText(): String =
  "${formatByteSize(shallowSize)} of its own, dominates ${formatObjectCount(dominatedObjectCount)}"

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
