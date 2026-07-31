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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import shark.explorer.CellSubject
import shark.explorer.DominatorKind
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectKind
import shark.explorer.HeapObjectSummary
import shark.explorer.IndependentPaths
import shark.explorer.ObjectDominator
import shark.explorer.ObjectGroupKind
import shark.explorer.ObjectGroupSummary
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

/**
 * What the selected object is, beside whichever screen is showing.
 *
 * Everything here that leads somewhere leads there by navigating: clicking a dominator, a field or a
 * button moves the breadcrumbs too, so that what this panel describes is always what they name.
 */
@Composable
internal fun DetailsPanel(
  selection: Selection?,
  dominator: ObjectDominator?,
  paths: IndependentPaths?,
  /** The selected object's pixels, when it's a bitmap anything has the pixels of. */
  bitmap: ImageBitmap?,
  isStarred: Boolean,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  onShowPaths: (Long) -> Unit,
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
        is Selection.ObjectGroup -> ObjectGroupDetails(selection.summary, coloring, onOpen)
        is Selection.Object -> ObjectDetails(
          summary = selection.summary,
          dominator = dominator,
          paths = paths,
          bitmap = bitmap,
          isStarred = isStarred,
          coloring = coloring,
          onOpen = onOpen,
          onShowPaths = onShowPaths,
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
  coloring: CellColoring,
  onOpen: (Long) -> Unit
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
  Button(onClick = { onOpen(summary.nodeId) }) {
    Text("Zoom in")
  }
}

private fun ObjectGroupSummary.title(): String = when (kind) {
  ObjectGroupKind.GC_ROOTS -> HeapDominatorTreemap.GC_ROOTS_LABEL
  ObjectGroupKind.UNREACHABLE -> HeapDominatorTreemap.UNREACHABLE_LABEL
  ObjectGroupKind.CLASS -> "${formatObjectCount(objectCount)} of one class"
}

private fun ObjectGroupSummary.explanation(): String = when (kind) {
  ObjectGroupKind.GC_ROOTS -> GC_ROOTS_EXPLANATION
  ObjectGroupKind.UNREACHABLE -> UNREACHABLE_EXPLANATION
  ObjectGroupKind.CLASS -> CLASS_GROUP_EXPLANATION
}

@Composable
private fun ObjectDetails(
  summary: HeapObjectSummary,
  dominator: ObjectDominator?,
  paths: IndependentPaths?,
  bitmap: ImageBitmap?,
  isStarred: Boolean,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  onShowPaths: (Long) -> Unit,
  onListInstances: (String) -> Unit,
  onToggleStar: () -> Unit
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      summary.label,
      Modifier.weight(1f),
      style = MaterialTheme.typography.titleMedium,
      overflow = TextOverflow.Ellipsis
    )
    Hint(if (isStarred) UNSTAR_HINT else STAR_HINT) {
      Text(
        if (isStarred) STARRED_GLYPH else UNSTARRED_GLYPH,
        Modifier.clickable(onClick = onToggleStar).padding(4.dp),
        style = MaterialTheme.typography.titleMedium
      )
    }
  }
  Text(summary.className, style = MaterialTheme.typography.bodySmall)
  if (summary.objectId != HeapDominatorTreemap.ROOT_OBJECT_ID) {
    // Selectable so it can be copied out: an object id is how you point something else — a script, a
    // colleague, a bug report — at this one instance rather than at its class.
    SelectionContainer {
      Text(objectIdText(summary.objectId), style = MaterialTheme.typography.bodySmall)
    }
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
  Button(onClick = { onOpen(summary.objectId) }, enabled = summary.dominatedObjectCount > 0) {
    Text("Zoom in")
  }
  // A class is where its instances are found: nothing else in the window leads from one to all of them.
  if (summary.kind == HeapObjectKind.CLASS) {
    Button(onClick = { onListInstances(summary.className) }) {
      Text(LIST_INSTANCES)
    }
  }
  DominatorSection(dominator, onOpen)
  IndependentPathsSection(summary.objectId, paths, dominator, onShowPaths)
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

/**
 * The one node the tree attributes the object's bytes to.
 *
 * Not the same question as what points at the object, and not the same answer: several objects can hold it
 * while exactly one dominates it. Which is worth explaining rather than assuming, hence the hint.
 */
@Composable
private fun DominatorSection(
  dominator: ObjectDominator?,
  onOpen: (Long) -> Unit
) {
  if (dominator == null) {
    return
  }
  SectionHeading(DOMINATOR, dominator.hint())
  Inspectable(
    text = "${dominator.label} · ${formatByteSize(dominator.retainedSize)}",
    objectId = dominator.nodeId,
    onInspect = onOpen
  )
}

private fun ObjectDominator.hint(): String = when (kind) {
  DominatorKind.OBJECT -> DOMINATOR_HINT
  DominatorKind.ALL_GC_ROOTS -> ALL_GC_ROOTS_DOMINATOR_HINT
  DominatorKind.UNCOLLECTED_GARBAGE -> GARBAGE_DOMINATOR_HINT
}

/**
 * How many ways the object is held below its dominator, and a way to go and read them.
 *
 * A screen of its own rather than lines here, because a path is a chain of objects with something to say
 * about each of them and this panel is one column wide. See [PathsScreen].
 */
@Composable
private fun IndependentPathsSection(
  objectId: Long,
  paths: IndependentPaths?,
  dominator: ObjectDominator?,
  onShowPaths: (Long) -> Unit
) {
  if (dominator == null) {
    return
  }
  SectionHeading(INDEPENDENT_PATHS, INDEPENDENT_PATHS_HINT)
  when {
    paths == null -> Text(SEARCHING_PATHS, style = MaterialTheme.typography.bodySmall)
    paths.isStraightFromDominator(dominator) ->
      Text(NO_PATHS, style = MaterialTheme.typography.bodySmall)
    paths.paths.isEmpty() -> Text(NO_PATH_FOUND, style = MaterialTheme.typography.bodySmall)
    else -> Button(onClick = { onShowPaths(objectId) }) {
      Text(paths.buttonText())
    }
  }
}

/**
 * Whether the one path is the dominator's own field pointing at the object, which the panel has nothing to
 * add to: the dominator is the line right above.
 *
 * A single step below a group is a different thing — there the step is the GC root's own object, and which
 * kind of root reaches it is the whole answer — and so is an empty list, which means the search found
 * nothing to say.
 */
private fun IndependentPaths.isStraightFromDominator(dominator: ObjectDominator): Boolean =
  dominator.kind == DominatorKind.OBJECT && paths.singleOrNull()?.steps?.size == 1

/** How many paths the button offers to show, and whether the search stopped with more of them left. */
private fun IndependentPaths.buttonText(): String {
  val count = if (paths.size == 1) "1 path" else "${paths.size} paths"
  return if (hasMore) "Show $count or more" else "Show the $count"
}

/** The name of a section, and a question mark that explains it without a paragraph in the way. */
@Composable
private fun SectionHeading(
  name: String,
  hint: String
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(name, style = MaterialTheme.typography.labelSmall)
    Hint(hint) {
      Text(
        HINT_GLYPH,
        Modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelSmall
      )
    }
  }
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
 * How the panel prints an object id: decimal, which is what Shark's own APIs take, and hex, which is
 * what a heap dump records and every other heap analyzer prints.
 */
internal fun objectIdText(objectId: Long): String = "id $objectId · ${hexObjectId(objectId)}"

/** Hex alone, for the places that name an object in passing, like a breadcrumb. */
internal fun hexObjectId(objectId: Long): String = "0x${objectId.toString(16)}"

/** Shown by the details panel until something is selected. */
internal const val NO_SELECTION = "Click a rectangle or a sector to see what it retains."

/** The heading of the section naming the one node the tree attributes the object's bytes to. */
internal const val DOMINATOR = "Dominator"

/** What a dominator is, for the ordinary case where one object owns another. */
internal const val DOMINATOR_HINT =
  "The one object that would free this one: every path from a GC root here goes through it, so this " +
    "stays in memory for exactly as long as that does. Which is why the treemap draws this rectangle " +
    "inside that one, and why there is only ever one answer — several objects can point at this one " +
    "while exactly one dominates it."

/** And what it means when there isn't one, which is what puts a rectangle flat under the root. */
internal const val ALL_GC_ROOTS_DOMINATOR_HINT =
  "No single object would free this one: it's held from several places at once, on paths that meet " +
    "nowhere, so releasing any one of them would leave the others holding it. With no owner to " +
    "attribute its bytes to, the treemap draws it at the top of the reachable heap — and the paths " +
    "below say who those holders are."

internal const val GARBAGE_DOMINATOR_HINT =
  "No GC root reaches this, so nothing keeps it in memory: it's garbage that hadn't been collected " +
    "when the heap dump was written. Whatever points at it is garbage as well."

/** What a class leads to that nothing else does: every instance of it. */
internal const val LIST_INSTANCES = "List the instances"

/** Hovering the question mark is how the panel explains a dominator without a paragraph in the way. */
private const val HINT_GLYPH = "?"

internal const val STARRED_GLYPH = "★"
internal const val UNSTARRED_GLYPH = "☆"
private const val STAR_HINT = "Star this object, to compare it with others later."
private const val UNSTAR_HINT = "Remove this object from the starred list."

internal const val GC_ROOTS_EXPLANATION =
  "Not one object: everything the garbage collector reaches, so everything that is still in memory " +
    "on purpose."

internal const val UNREACHABLE_EXPLANATION =
  "Not one object: everything no GC root reaches, so garbage that hadn't been collected when the heap " +
    "dump was written. The next collection would take all of it."

internal const val CLASS_GROUP_EXPLANATION =
  "Not one object: these are all the instances of this class that nothing owns on its own, gathered " +
    "so the root's children can be read. Zoom in to see them one by one."

private const val GROUP_EXPLANATION =
  "Too small or too many to draw one by one. Zoom into what holds them to see them."

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
