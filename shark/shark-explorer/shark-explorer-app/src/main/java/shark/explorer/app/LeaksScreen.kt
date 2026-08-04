package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import shark.explorer.HeapLeaks
import shark.explorer.LeakGroup
import shark.explorer.LeakKind
import shark.explorer.LeakSection
import shark.explorer.LeakingObject
import shark.explorer.WatchedObject
import shark.explorer.formatByteSize
import shark.explorer.hexObjectId

/**
 * Every leaking object of the heap dump, in three parts: the app's own leaks, the ones in code it doesn't
 * control, and the objects that were meant to be gone and are.
 *
 * One row per leak rather than per object, because a leak with fifty instances is one thing to fix; the row
 * unfolds into them when there is more than one. Every row leads into the object explorer — a leak is a
 * chain from a GC root, and the chain the map already draws is exactly that, so there is no leak trace here
 * and nothing to read twice.
 */
@Composable
internal fun LeaksScreen(
  leaks: HeapLeaks,
  isFindingLeaks: Boolean,
  coloring: CellColoring,
  expandedGroups: Set<String>,
  onToggleGroup: (String) -> Unit,
  onOpen: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxSize()) {
      Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          if (isFindingLeaks) LOOKING_FOR_LEAKS else leaks.countText(),
          style = MaterialTheme.typography.bodySmall
        )
        if (isFindingLeaks) {
          CircularProgressIndicator(Modifier.size(SPINNER_SIZE), strokeWidth = SPINNER_STROKE)
        }
      }
      HorizontalDivider()
      LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        leaks.sections.forEach { section ->
          item(key = section.kind.name) {
            SectionHeader(section)
          }
          section.groups.forEach { group ->
            val groupKey = section.kind.groupKey(group)
            // Folded rows are one item each, so that a leak with five hundred instances costs the list
            // one row until someone asks to see them.
            item(key = groupKey) {
              GroupRow(
                group = group,
                isExpanded = groupKey in expandedGroups,
                onToggle = { onToggleGroup(groupKey) },
                onOpen = onOpen
              )
            }
            if (group.objects.size == 1 || groupKey in expandedGroups) {
              group.objects.forEach { leakingObject ->
                item(key = "$groupKey ${leakingObject.objectId}") {
                  LeakingObjectRow(leakingObject, coloring, onOpen)
                }
              }
            }
          }
        }
      }
    }
  }
}

/** What one of the three parts is, and how much of the heap dump is in it. */
@Composable
private fun SectionHeader(section: LeakSection) {
  Column(
    Modifier.fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .padding(horizontal = 12.dp, vertical = 6.dp)
  ) {
    Text(
      "${section.kind.title} · ${section.summary()}",
      style = MaterialTheme.typography.titleSmall
    )
    Text(
      section.kind.explanation,
      style = MaterialTheme.typography.bodySmall,
      color = MUTED_TEXT
    )
  }
}

/**
 * One leak: what it is, how many objects of the heap dump are instances of it, and what they hold.
 *
 * The row itself leads to the first of them, which is the largest, because that is what someone reading a
 * list of leaks wants next; the triangle beside it is what opens the rest, and is only there when there is
 * a rest.
 */
@Composable
private fun GroupRow(
  group: LeakGroup,
  isExpanded: Boolean,
  onToggle: () -> Unit,
  onOpen: (Long) -> Unit
) {
  Row(
    Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (group.objects.size > 1) {
      Text(
        if (isExpanded) EXPANDED_ARROW else FOLDED_ARROW,
        Modifier.width(TOGGLE_WIDTH).clickableRow(onToggle),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center
      )
    } else {
      Spacer(Modifier.width(TOGGLE_WIDTH))
    }
    Column(
      Modifier.weight(1f).clickableRow { onOpen(group.objects.first().objectId) },
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      Text(
        group.title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      group.subtitle?.let { subtitle ->
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MUTED_TEXT,
          maxLines = MAX_SUBTITLE_LINES,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    Text(
      group.objectCountText(),
      style = MaterialTheme.typography.bodySmall,
      color = MUTED_TEXT
    )
    Text(
      formatByteSize(group.retainedSize),
      Modifier.width(SIZE_COLUMN_WIDTH),
      style = MaterialTheme.typography.bodyMedium,
      textAlign = TextAlign.End
    )
  }
}

/** One leaking object: which one it is, what it holds, and what LeakCanary was told about it. */
@Composable
private fun LeakingObjectRow(
  leakingObject: LeakingObject,
  coloring: CellColoring,
  onOpen: (Long) -> Unit
) {
  Column(Modifier.fillMaxWidth().padding(start = INSTANCE_INSET, end = 12.dp, bottom = 4.dp)) {
    Row(
      Modifier.fillMaxWidth().clickableRow { onOpen(leakingObject.objectId) }
        .padding(vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(Modifier.size(SWATCH_SIZE).background(legendColor(coloring, leakingObject.strength)))
      Column(Modifier.weight(1f)) {
        Text(
          leakingObject.identityText(),
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        leakingObject.headline?.let { headline ->
          Text(
            headline,
            style = MaterialTheme.typography.bodySmall,
            color = MUTED_TEXT,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
      Text(
        formatByteSize(leakingObject.retainedSize),
        Modifier.width(SIZE_COLUMN_WIDTH),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.End
      )
    }
    leakingObject.watcher?.let { watcher ->
      WatcherRow(watcher, onOpen)
    }
  }
}

/**
 * What LeakCanary's watcher recorded about this object, and the weak reference it recorded it in.
 *
 * A line of its own rather than a label, because it leads somewhere: the `KeyedWeakReference` is an object
 * of the heap dump like any other, and it is also what the map draws the leaking object underneath, so
 * being able to open it is being able to see the leak from the watcher's side.
 */
@Composable
private fun WatcherRow(
  watcher: WatchedObject,
  onOpen: (Long) -> Unit
) {
  Column(
    Modifier.fillMaxWidth().clickableRow { onOpen(watcher.weakReferenceObjectId) }
      .padding(bottom = 2.dp)
  ) {
    Text(watcher.watchText(), style = MaterialTheme.typography.bodySmall, color = LINK_COLOR)
    if (watcher.description.isNotEmpty()) {
      Text(
        watcher.description,
        style = MaterialTheme.typography.bodySmall,
        color = MUTED_TEXT,
        maxLines = MAX_SUBTITLE_LINES,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/** Which section a group is in as well as which group it is: two sections can hold the same title. */
private fun LeakKind.groupKey(group: LeakGroup): String = "$name ${group.id}"

/** How many leaks and how many objects, which is what says a leak is one thing and not fifty. */
private fun LeakSection.summary(): String = when {
  groups.isEmpty() -> NONE_FOUND
  else -> "${groups.size} ${if (groups.size == 1) LEAK else LEAKS}, " +
    "$objectCount ${if (objectCount == 1) OBJECT else OBJECTS}"
}

private fun HeapLeaks.countText(): String = when (objectCount) {
  0 -> NOTHING_LEAKING
  else -> "$objectCount leaking ${if (objectCount == 1) OBJECT else OBJECTS}"
}

private fun LeakGroup.objectCountText(): String =
  "${objects.size} ${if (objects.size == 1) OBJECT else OBJECTS}"

/** The class in full, then the address: the same two things every list of objects here shows. */
private fun LeakingObject.identityText() = buildAnnotatedString {
  withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(className.substringAfterLast('.')) }
  withStyle(SpanStyle(color = MUTED_TEXT)) {
    append(" ${kind.typeName} · ${hexObjectId(objectId)}")
  }
}

/**
 * What the watcher knew: the key it logged the object under, how long before the dump it was handed over,
 * and how long it had been retained. The durations are missing from heap dumps written before 2.0 alpha 3.
 */
private fun WatchedObject.watchText(): String = buildString {
  append("$WATCHED_GLYPH Watched · key $key")
  watchDurationMillis?.let { append(" · handed over ${formatDuration(it)} before the dump") }
  retainedDurationMillis?.takeIf { isRetained }
    ?.let { append(" · retained for ${formatDuration(it)}") }
}

/** Seconds, since these are the seconds between an app letting go of an object and the dump. */
private fun formatDuration(millis: Long): String = "${millis / MILLIS_PER_SECOND} s"

private const val MILLIS_PER_SECOND = 1000L

/** Shown while the pass over every object of the heap dump is still running. */
private const val LOOKING_FOR_LEAKS = "Going through the heap dump…"

private const val NOTHING_LEAKING = "Nothing in this heap dump is leaking."
private const val NONE_FOUND = "none"

private const val LEAK = "leak"
private const val LEAKS = "leaks"
private const val OBJECT = "object"
private const val OBJECTS = "objects"

/** What the line about the watcher starts with, so it reads as the watcher's line and not the object's. */
private const val WATCHED_GLYPH = "◉"

internal const val FOLDED_ARROW = "▸"
internal const val EXPANDED_ARROW = "▾"

/** Wide enough for the triangle, and the same width when there is none, so the titles line up. */
private val TOGGLE_WIDTH = 16.dp

/** Enough that the objects of a leak read as being inside it rather than as more leaks. */
private val INSTANCE_INSET = 28.dp

/** Wide enough for a size in gigabytes, so that the numbers line up down the column. */
private val SIZE_COLUMN_WIDTH = 72.dp

/** A library leak's description is a paragraph, and a list of leaks has room for the start of it. */
private const val MAX_SUBTITLE_LINES = 3

private val SPINNER_SIZE = 12.dp
private val SPINNER_STROKE = 2.dp
