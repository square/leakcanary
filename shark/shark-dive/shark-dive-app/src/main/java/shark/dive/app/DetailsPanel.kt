package shark.dive.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
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
import shark.dive.CellSubject
import shark.dive.HeapObjectKind
import shark.dive.HeapObjectSummary
import shark.dive.ObjectGroupSummary
import shark.dive.ReachabilityStrength
import shark.dive.Topic
import shark.dive.formatByteSize
import shark.dive.formatByteSizeOfTotal
import shark.dive.formatObjectCount

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
  /** What a retained size here is a share of. See [shark.dive.HeapSizes.stronglyReachableByteCount]. */
  stronglyReachableByteCount: Long,
  /** The selected object's pixels, when it's a bitmap anything has the pixels of. */
  bitmap: ImageBitmap?,
  isStarred: Boolean,
  /** Whether the selected object is meant to be in memory, and null when nothing is selected. */
  leakStatus: ObjectLeakStatus?,
  /** Whether the statuses set by hand have been read yet, which is what lets one be changed. */
  isLeakStatusRead: Boolean,
  /** What went wrong reading or writing them, shown under the status it is about. */
  leakStatusProblem: String?,
  onChangeLeakStatus: () -> Unit,
  onOpen: (Long, OpenIn) -> Unit,
  /** Puts a link to a field's object on the clipboard, beside opening it. See [OpenTarget]. */
  onCopyLink: (Long) -> Unit,
  onListInstances: (String) -> Unit,
  onToggleStar: () -> Unit,
  /** Where the `?` beside how firmly an object is held goes. See [Explain]. */
  onExplain: (Topic) -> Unit,
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
            formatObjectCount(selection.nodeCount),
            style = MaterialTheme.typography.titleMedium
          )
          Text("Held by ${selection.parentLabel}", style = MaterialTheme.typography.bodySmall)
          Detail("Retained", formatByteSizeOfTotal(selection.byteCount, stronglyReachableByteCount))
        }
        is Selection.ObjectGroup ->
          ObjectGroupDetails(selection.summary, stronglyReachableByteCount, onExplain)
        is Selection.Object -> ObjectDetails(
          summary = selection.summary,
          stronglyReachableByteCount = stronglyReachableByteCount,
          bitmap = bitmap,
          isStarred = isStarred,
          leakStatus = leakStatus,
          isLeakStatusRead = isLeakStatusRead,
          leakStatusProblem = leakStatusProblem,
          onChangeLeakStatus = onChangeLeakStatus,
          onOpen = onOpen,
          onCopyLink = onCopyLink,
          onListInstances = onListInstances,
          onToggleStar = onToggleStar,
          onExplain = onExplain
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
 * root. Its title is a count, which is what says it is not one object.
 *
 * The same rows an object gets, in the same order, so that a pile and an object read as two of a kind
 * rather than as two panels: how firmly it is held, then what it costs.
 */
@Composable
private fun ObjectGroupDetails(
  summary: ObjectGroupSummary,
  stronglyReachableByteCount: Long,
  onExplain: (Topic) -> Unit
) {
  Text(summary.title(), style = MaterialTheme.typography.titleMedium)
  summary.className?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  StrengthRow(summary.strength, onExplain)
  // How many objects is the title, so this row is the bytes and nothing else. The same word an object gets,
  // rather than "Retained together": that a pile of objects retains something together is what makes it a
  // pile, and it is already headed by a count.
  Detail(RETAINED, formatByteSizeOfTotal(summary.retainedSize, stronglyReachableByteCount))
}

/**
 * What a pile of objects is called wherever it is described: here, and on the card at the pointer.
 *
 * **A count, and the same count whichever kind of pile it is.** Which kind it is is the line under it — the
 * class name for a pile of one class, and the strength for the uncollected garbage — so a second word for it
 * here would be that line said twice, in a different vocabulary each time.
 */
internal fun ObjectGroupSummary.title(): String = formatObjectCount(objectCount)

/**
 * How firmly the thing being described is held: the colour the map draws it in, and the one name that colour
 * has. See [ReachabilityStrength.label].
 *
 * One composable rather than the same row written out per panel, because the swatch beside the word is the
 * whole of what ties the panel to the map — a panel that draws the swatch a pixel differently from another
 * is a panel a reader has to check against the legend again.
 */
@Composable
private fun StrengthRow(
  strength: ReachabilityStrength,
  onExplain: (Topic) -> Unit
) {
  Explain(Topic.REACHABILITY_STRENGTH, onExplain) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(Modifier.size(SWATCH_SIZE).background(objectStrengthColor(strength)))
      Text(strength.label, style = MaterialTheme.typography.bodySmall)
    }
  }
}

@Composable
private fun ObjectDetails(
  summary: HeapObjectSummary,
  stronglyReachableByteCount: Long,
  bitmap: ImageBitmap?,
  isStarred: Boolean,
  leakStatus: ObjectLeakStatus?,
  isLeakStatusRead: Boolean,
  leakStatusProblem: String?,
  onChangeLeakStatus: () -> Unit,
  onOpen: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit,
  onListInstances: (String) -> Unit,
  onToggleStar: () -> Unit,
  onExplain: (Topic) -> Unit
) {
  Hint(if (isStarred) UNSTAR_HINT else STAR_HINT) {
    Text(
      if (isStarred) "$STARRED_GLYPH $STARRED" else "$UNSTARRED_GLYPH $NOT_STARRED",
      Modifier.clickableRow(onClick = onToggleStar).padding(vertical = 2.dp),
      style = MaterialTheme.typography.bodyMedium
    )
  }
  summary.headline?.let { headline ->
    Text(headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
  }
  // Above everything measured about the object, because it is the one line here that is a conclusion:
  // the sizes and the fields below it are what it was concluded from. Above the picture of a bitmap too,
  // so that a screenshot several hundred pixels tall can't push the conclusion out of the panel.
  if (leakStatus != null) {
    LeakStatusDetail(
      status = leakStatus,
      isRead = isLeakStatusRead,
      problem = leakStatusProblem,
      onChange = onChangeLeakStatus
    )
  }
  // Under the headline, which for a bitmap is its size and its format: the picture is what the bitmap is,
  // and the sentence describing it stops just short of saying it.
  BitmapPreview(bitmap)
  StrengthRow(summary.strength, onExplain)
  Detail(RETAINED, retainedText(summary.retainedSize, summary.retainedCount, stronglyReachableByteCount))
  // No share of the total on the shallow size: what one object is made of on its own is never a
  // meaningful fraction of a heap dump, and a second percentage in the column would only dilute the
  // one that says something.
  Detail(SHALLOW, formatByteSize(summary.shallowSize))
  summary.inspectorLabels.forEach { label ->
    Text(label, style = MaterialTheme.typography.bodySmall)
  }
  // A class is where its instances are found: nothing else in the window leads from one to all of them.
  if (summary.kind == HeapObjectKind.CLASS) {
    Button(onClick = { onListInstances(summary.className) }) {
      Text(LIST_INSTANCES)
    }
  }
  Fields(summary, onOpen, onCopyLink)
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

/**
 * Whatever it wraps, with [text] shown while the pointer rests on it.
 *
 * [footer] is a line under it in the colour of a link, for a hint that leads somewhere: what a hint says
 * about clicking has to be said in the hint, because the tooltip itself cannot be clicked — its [Surface]
 * swallows pointer events, which is the same thing that keeps [PointerCard] out from under the pointer. See
 * [Explain], which is the one thing that passes one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Hint(
  text: String,
  footer: String? = null,
  content: @Composable () -> Unit
) {
  TooltipArea(
    tooltip = {
      Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
        Column(
          Modifier.width(HINT_WIDTH).padding(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Text(text, style = MaterialTheme.typography.bodySmall)
          footer?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = LINK_COLOR)
          }
        }
      }
    },
    content = content
  )
}

/** Every field of the object, so that the panel says what the heap dump holds and not just its shape. */
@Composable
private fun Fields(
  summary: HeapObjectSummary,
  onInspect: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit
) {
  if (summary.fields.isEmpty()) {
    return
  }
  Text("Fields", style = MaterialTheme.typography.labelSmall)
  summary.fields.forEach { field ->
    Inspectable("${field.name} = ${field.value}", field.inspectableObjectId, onInspect, onCopyLink)
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
  onInspect: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit
) {
  if (objectId == null) {
    Text(text, style = MaterialTheme.typography.bodySmall)
  } else {
    val open: (OpenIn) -> Unit = { openIn -> onInspect(objectId, openIn) }
    OpenTarget(open, { onCopyLink(objectId) }) {
      Text(
        text,
        Modifier.openable(open),
        style = MaterialTheme.typography.bodySmall,
        color = LINK_COLOR
      )
    }
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
 * What an object's two sizes are called wherever they are given: the rows of this panel, the lines of the
 * card at the pointer, and the columns of every list of objects. See [ObjectRow].
 *
 * **One word each.** These were three vocabularies for two numbers — `Retained` here, `Retains … in N
 * objects` on the card, `Retained` again over a column — and a reader comparing a card against the panel
 * behind it had to work out that they were the same numbers before they could compare them.
 *
 * How many objects that is goes on the [RETAINED] line rather than in a row of its own, because it is the
 * same fact counted the other way. And how many the object *immediately* dominates is gone from both: that
 * is the number of rectangles drawn inside this one, which is what the picture beside them is.
 */
internal const val RETAINED = "Retained"
internal const val SHALLOW = "Shallow"

internal fun retainedText(
  retainedSize: Long,
  retainedCount: Int,
  stronglyReachableByteCount: Long
): String = "${formatByteSizeOfTotal(retainedSize, stronglyReachableByteCount)} · " +
  formatObjectCount(retainedCount)

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
