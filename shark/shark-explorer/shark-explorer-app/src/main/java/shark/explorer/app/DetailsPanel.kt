package shark.explorer.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import shark.explorer.CellSubject
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectKind
import shark.explorer.HeapObjectSummary
import shark.explorer.ObjectGroupKind
import shark.explorer.ObjectGroupSummary
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

/**
 * What the clicked object holds, beside the map.
 *
 * The clicked one and never the one under the pointer: this panel is a column of everything there is to say
 * about an object, several screens tall on a real one, and having it follow the mouse across the map made it
 * unreadable. What the pointer is on gets a card at the pointer — see [PointerCard] — and a few more steps on
 * the end of the chain beside the map, see [RootPathPanel].
 *
 * **Which object it is, is not here**: that is the bar above the map, where it stays whichever screen is
 * showing. This panel is the rest of the answer, so it starts where the numbers do.
 *
 * Everything here that leads somewhere leads there by navigating, so that what this panel describes stays
 * what the window is showing: clicking a field moves the map to that object as well.
 */
@Composable
internal fun DetailsPanel(
  selection: Selection?,
  /** The selected object's pixels, when it's a bitmap anything has the pixels of. */
  bitmap: ImageBitmap?,
  isStarred: Boolean,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  onListInstances: (String) -> Unit,
  onToggleStar: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      when (selection) {
        null -> Text(NO_SELECTION, style = MaterialTheme.typography.bodyMedium)
        is Selection.Group -> {
          Text(
            "${selection.nodeCount} smaller objects",
            style = MaterialTheme.typography.titleMedium
          )
          Text(
            "Held by ${selection.parentLabel}. $GROUP_EXPLANATION",
            style = MaterialTheme.typography.bodySmall
          )
          Detail("Retained", formatByteSize(selection.byteCount))
        }
        is Selection.ObjectGroup -> ObjectGroupDetails(selection.summary, coloring)
        is Selection.Object -> ObjectDetails(
          summary = selection.summary,
          bitmap = bitmap,
          isStarred = isStarred,
          coloring = coloring,
          onOpen = onOpen,
          onListInstances = onListInstances,
          onToggleStar = onToggleStar
        )
      }
    }
  }
}

/** What the details panel is showing. */
internal sealed interface Selection {

  data class Object(val summary: HeapObjectSummary) : Selection

  /** A cell standing for many objects was clicked, so there's no one object to describe. */
  data class ObjectGroup(val summary: ObjectGroupSummary) : Selection

  /** A [CellSubject.Group] was clicked, so there's no one object to describe. */
  data class Group(
    val nodeCount: Int,
    val byteCount: Long,
    val parentLabel: String
  ) : Selection
}

/**
 * A cell standing for many objects: half of the heap dump, or every instance of one class under the
 * root. Says so in as many words, because a rectangle that isn't an object looks exactly like one that
 * is until something says otherwise.
 */
@Composable
private fun ObjectGroupDetails(
  summary: ObjectGroupSummary,
  coloring: CellColoring
) {
  Text(summary.title(), style = MaterialTheme.typography.titleMedium)
  summary.className?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(Modifier.size(SWATCH_SIZE).background(legendColor(coloring, summary.strength)))
    Text(summary.explanation(), style = MaterialTheme.typography.bodySmall)
  }
  Detail("Retained together", formatByteSize(summary.retainedSize))
  Detail("Objects", formatObjectCount(summary.objectCount))
}

private fun ObjectGroupSummary.title(): String = when (kind) {
  ObjectGroupKind.UNREACHABLE -> HeapDominatorTreemap.UNREACHABLE_LABEL
  ObjectGroupKind.CLASS -> "${formatObjectCount(objectCount)} of one class"
}

private fun ObjectGroupSummary.explanation(): String = when (kind) {
  ObjectGroupKind.UNREACHABLE -> UNREACHABLE_EXPLANATION
  ObjectGroupKind.CLASS -> CLASS_GROUP_EXPLANATION
}

@Composable
private fun ObjectDetails(
  summary: HeapObjectSummary,
  bitmap: ImageBitmap?,
  isStarred: Boolean,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  onListInstances: (String) -> Unit,
  onToggleStar: () -> Unit
) {
  Hint(if (isStarred) UNSTAR_HINT else STAR_HINT) {
    Text(
      if (isStarred) "$STARRED_GLYPH $STARRED" else "$UNSTARRED_GLYPH $NOT_STARRED",
      Modifier.clickableRow(onToggleStar).padding(vertical = 2.dp),
      style = MaterialTheme.typography.bodyMedium
    )
  }
  summary.headline?.let { headline ->
    Text(headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
  }
  // Right under the headline, which for a bitmap is its size and its format: the picture is what the
  // bitmap is, and the sentence describing it stops just short of saying it.
  BitmapPreview(bitmap)
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(Modifier.size(SWATCH_SIZE).background(legendColor(coloring, summary.strength)))
    Text(summary.strength.reachabilityText, style = MaterialTheme.typography.bodySmall)
  }
  Detail("Retained", formatByteSize(summary.retainedSize))
  Detail("Retained objects", summary.retainedCount.toString())
  Detail("Shallow", formatByteSize(summary.shallowSize))
  Detail("Dominates", "${summary.dominatedObjectCount} objects")
  summary.inspectorLabels.forEach { label ->
    Text(label, style = MaterialTheme.typography.bodySmall)
  }
  // A class is where its instances are found: nothing else in the window leads from one to all of them.
  if (summary.kind == HeapObjectKind.CLASS) {
    Button(onClick = { onListInstances(summary.className) }) {
      Text(LIST_INSTANCES)
    }
  }
  Fields(summary, onOpen)
}

/**
 * What the selected bitmap looks like, as wide as the panel and never stretched.
 *
 * On grey rather than on the panel, because the transparent pixels of a bitmap take the colour of
 * whatever is behind them: an icon drawn in white and an icon drawn in black both have to show, and one
 * of them would disappear against anything at either end.
 */
@Composable
private fun BitmapPreview(bitmap: ImageBitmap?) {
  if (bitmap == null) {
    return
  }
  Image(
    bitmap = bitmap,
    contentDescription = BITMAP_DESCRIPTION,
    modifier = Modifier.fillMaxWidth()
      .heightIn(max = BITMAP_PREVIEW_MAX_HEIGHT)
      .background(BITMAP_BACKGROUND),
    contentScale = ContentScale.Fit
  )
}

/** Whatever it wraps, with [text] shown while the pointer rests on it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Hint(
  text: String,
  content: @Composable () -> Unit
) {
  TooltipArea(
    tooltip = {
      Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
        Text(
          text,
          Modifier.width(HINT_WIDTH).padding(8.dp),
          style = MaterialTheme.typography.bodySmall
        )
      }
    },
    content = content
  )
}

/** Every field of the object, so that the panel says what the heap dump holds and not just its shape. */
@Composable
private fun Fields(
  summary: HeapObjectSummary,
  onInspect: (Long) -> Unit
) {
  if (summary.fields.isEmpty()) {
    return
  }
  Text("Fields", style = MaterialTheme.typography.labelSmall)
  summary.fields.forEach { field ->
    Inspectable("${field.name} = ${field.value}", field.inspectableObjectId, onInspect)
  }
  if (summary.hiddenFieldCount > 0) {
    Text(
      "and ${summary.hiddenFieldCount} more",
      style = MaterialTheme.typography.bodySmall
    )
  }
}

/** A line of the panel that leads somewhere: clicking it shows that object instead. */
@Composable
internal fun Inspectable(
  text: String,
  objectId: Long?,
  onInspect: (Long) -> Unit
) {
  if (objectId == null) {
    Text(text, style = MaterialTheme.typography.bodySmall)
  } else {
    Text(
      text,
      Modifier.clickable { onInspect(objectId) },
      style = MaterialTheme.typography.bodySmall,
      color = LINK_COLOR
    )
  }
}

@Composable
internal fun Detail(
  name: String,
  value: String
) {
  Column {
    Text(name, style = MaterialTheme.typography.labelSmall)
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}

/**
 * Shown by the details panel until something has been clicked, which is what it describes: pointing at a
 * rectangle says what it is at the pointer instead, and adds the chain holding it to the one beside the map.
 * See [PointerCard] and [RootPathPanel].
 */
internal const val NO_SELECTION = "Click a rectangle or a sector to see what it retains."

/** What a class leads to that nothing else does: every instance of it. */
internal const val LIST_INSTANCES = "List the instances"

internal const val STARRED_GLYPH = "★"
internal const val UNSTARRED_GLYPH = "☆"
private const val STARRED = "Starred"
private const val NOT_STARRED = "Star this object"
private const val STAR_HINT = "Star this object, to compare it with others later."
private const val UNSTAR_HINT = "Remove this object from the starred list."

internal const val UNREACHABLE_EXPLANATION =
  "Not one object: everything no GC root reaches, so garbage that hadn't been collected when the heap " +
    "dump was written. The next collection would take all of it."

internal const val CLASS_GROUP_EXPLANATION =
  "Not one object: these are all the instances of this class that nothing owns on its own, gathered " +
    "so the root's children can be read. Click it to see them one by one."

private const val GROUP_EXPLANATION =
  "Too small or too many to draw one by one. Click what holds them to see them."

/** What the pixels of the selected bitmap are, to anything that can't look at them. */
internal const val BITMAP_DESCRIPTION = "The pixels of the selected bitmap."

internal val DETAILS_WIDTH = 320.dp

/** Tall enough for a portrait screenshot to be recognisable, short enough to leave the fields in view. */
private val BITMAP_PREVIEW_MAX_HEIGHT = 320.dp

/** Neither end of the range, so that neither a white bitmap nor a black one vanishes into it. */
private val BITMAP_BACKGROUND = Color(0xFF808080)

/** Wide enough for the hints to read as paragraphs rather than as one long line. */
private val HINT_WIDTH = 320.dp

/** Panel lines that lead to another object, coloured like a link because that's what they are. */
internal val LINK_COLOR = SELECTION_COLOR
internal val SWATCH_SIZE = 10.dp
