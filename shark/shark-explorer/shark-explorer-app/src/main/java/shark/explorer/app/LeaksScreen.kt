package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
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
        leaks.sections.forEachIndexed { sectionIndex, section ->
          // Pinned while its own leaks scroll under it, which is what says they are its: a heading that
          // scrolls away leaves a list of rows with nothing above them saying which of the three they are.
          stickyHeader(key = section.kind.name) {
            SectionHeader(section, isFirst = sectionIndex == 0)
          }
          section.groups.forEach { group ->
            val groupKey = section.kind.groupKey(group)
            val isExpanded = groupKey in expandedGroups
            val hasMore = group.objects.size > 1
            item(key = groupKey) { GroupRow(group) }
            // The first object always: a leak is a reference, and a reference with nothing under it says
            // what shouldn't be holding without ever saying what it is holding.
            item(key = "$groupKey ${group.objects.first().objectId}") {
              LeakingObjectRow(
                leakingObject = group.objects.first(),
                isLast = !hasMore,
                onOpen = onOpen
              )
            }
            if (hasMore) {
              // The rest are one item each behind this, so that a leak with five hundred instances costs
              // the list two rows until someone asks to see them.
              item(key = "$groupKey $MORE_KEY") {
                MoreObjectsRow(
                  count = group.objects.size - 1,
                  isExpanded = isExpanded,
                  onToggle = { onToggleGroup(groupKey) }
                )
              }
              if (isExpanded) {
                group.objects.drop(1).forEachIndexed { index, leakingObject ->
                  item(key = "$groupKey ${leakingObject.objectId}") {
                    LeakingObjectRow(
                      leakingObject = leakingObject,
                      isLast = index == group.objects.size - 2,
                      onOpen = onOpen
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

/**
 * What one of the three parts is, and how much of the heap dump is in it.
 *
 * The gap and the rule are above it and nothing is below it, which is the whole of what says the leaks
 * under it are its: a heading the same distance from the section above and the one below belongs to
 * neither. The band is opaque for the same reason the header is pinned — rows scroll under it.
 */
@Composable
private fun SectionHeader(
  section: LeakSection,
  /** Whether it opens the list, where there is nothing above to be told apart from. */
  isFirst: Boolean
) {
  Column(
    Modifier.fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .padding(top = if (isFirst) 0.dp else SECTION_GAP)
  ) {
    HorizontalDivider(thickness = SECTION_RULE_WIDTH, color = SECTION_RULE_COLOR)
    Column(
      Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.secondaryContainer)
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
}

/**
 * One leak: the references it is, how many objects of the heap dump it left behind, and what they hold.
 *
 * A heading rather than something to press. What a leak *is* is the references, and they are the same for
 * every object under it; the objects are what lead somewhere, and the first of them is on the row below
 * this one whether the leak has one or fifty. See [MoreObjectsRow] for the rest.
 */
@Composable
private fun GroupRow(group: LeakGroup) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    SectionBar()
    Row(
      Modifier.weight(1f).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Hint(NAME_HINT) {
          Text(
            group.nameText(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        group.subtitle?.let { subtitle ->
          Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MUTED_TEXT,
            maxLines = MAX_SUBTITLE_LINES,
            overflow = TextOverflow.Ellipsis
          )
        }
        // What makes a leak something to write down: the addresses in this list are of one heap dump, and
        // this is the same for the same leak in the next one. See [LeakGroup.signature].
        Hint(SIGNATURE_HINT) {
          Text(
            "$SIGNATURE ${group.signature}",
            style = MaterialTheme.typography.bodySmall,
            color = MUTED_TEXT,
            maxLines = 1,
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
}

/**
 * The objects of a leak past the first, and the one thing on this screen that opens and closes.
 *
 * Says how many rather than showing them, because a leak with five hundred instances of it is one thing to
 * fix and a list that scrolls for a minute to get past it says the opposite. Inside the leak, styled like
 * the objects it stands for, so that what opens is plainly more of the rows above it and not more leaks.
 */
@Composable
private fun MoreObjectsRow(
  count: Int,
  isExpanded: Boolean,
  onToggle: () -> Unit
) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(INSTANCE_BACKGROUND)) {
    SectionBar()
    Spacer(Modifier.width(INSTANCE_INSET - SECTION_BAR_WIDTH))
    InstanceRule(isLast = !isExpanded)
    Row(
      Modifier.weight(1f).clickableRow(onToggle)
        .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        if (isExpanded) EXPANDED_ARROW else FOLDED_ARROW,
        Modifier.width(TOGGLE_WIDTH),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center
      )
      Text(
        moreObjectsText(count),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        color = MUTED_TEXT
      )
    }
  }
}

/**
 * The bar down the left of every row of one part of the list, which is what says those rows are its.
 *
 * Three headings on a flat list say as much about the rows above them as about the rows below, and a
 * heading is the one thing on this screen that has to be unambiguous: an app leak and a library leak are
 * two different things to do about a row that otherwise looks the same. The bar ends where the part's last
 * row does, so it draws the part's height without anything having to know how tall that is.
 */
@Composable
private fun SectionBar() {
  Box(
    Modifier.width(SECTION_BAR_WIDTH)
      .fillMaxHeight()
      // The heading's own colour, which is what makes it the heading's bar rather than a border.
      .background(MaterialTheme.colorScheme.secondaryContainer)
  )
}

/**
 * One leaking object: which one it is, what it holds, and what LeakCanary was told about it.
 *
 * Inside the unfolded leak rather than under it, which is a shade behind the objects and a rule down the
 * left of them: a list of leaks whose rows are one indent apart reads as a list of leaks either way, and
 * which of these rows is the leak and which is an instance of it is the one thing it has to say.
 */
@Composable
private fun LeakingObjectRow(
  leakingObject: LeakingObject,
  /** Whether it closes the leak it is in, which is where the rule down the objects stops. */
  isLast: Boolean,
  onOpen: (Long) -> Unit
) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(INSTANCE_BACKGROUND)) {
    SectionBar()
    Spacer(Modifier.width(INSTANCE_INSET - SECTION_BAR_WIDTH))
    InstanceRule(isLast)
    Column(Modifier.weight(1f).padding(start = 8.dp, end = 12.dp, bottom = 4.dp)) {
      Row(
        Modifier.fillMaxWidth().clickableRow { onOpen(leakingObject.objectId) }
          .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(Modifier.size(SWATCH_SIZE).background(objectStrengthColor(leakingObject.strength)))
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
          // Why *this* object shouldn't be here, which the inspector that recognized it read off the object
          // itself: two objects of one leak can be leaking for reasons that don't read the same.
          leakingObject.leakingReason?.let { reason ->
            Text(
              reason,
              style = MaterialTheme.typography.bodySmall,
              color = MUTED_TEXT,
              maxLines = MAX_SUBTITLE_LINES,
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
        WatcherRow(watcher, alreadySaid = leakingObject.leakingReason.orEmpty(), onOpen = onOpen)
      }
    }
  }
}

/**
 * The rule down the left of everything inside one leak, which is what ties those rows to it.
 *
 * Stops short of the bottom on the row that closes the leak, so the rule draws where the leak ends rather
 * than running into the next one.
 */
@Composable
private fun InstanceRule(isLast: Boolean) {
  Box(
    Modifier.width(INSTANCE_RULE_WIDTH)
      .fillMaxHeight()
      .padding(bottom = if (isLast) INSTANCE_RULE_TAIL else 0.dp)
      .background(INSTANCE_RULE_COLOR)
  )
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
  /**
   * What the row above it already says about this object, which for a watched one is a sentence the
   * inspector built out of the watcher's own description — so printing it again is printing it twice.
   */
  alreadySaid: String,
  onOpen: (Long) -> Unit
) {
  Column(
    Modifier.fillMaxWidth().clickableRow { onOpen(watcher.weakReferenceObjectId) }
      .padding(bottom = 2.dp)
  ) {
    Text(watcher.watchText(), style = MaterialTheme.typography.bodySmall, color = LINK_COLOR)
    if (watcher.description.isNotEmpty() && watcher.description !in alreadySaid) {
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
private fun LeakKind.groupKey(group: LeakGroup): String = "$name ${group.signature}"

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

/**
 * Both ends of what the leak is, and a gap for whatever is between them.
 *
 * One line rather than two, because the ends are the same reference for most leaks and a second line that
 * repeats the first says the row has two names. Which end is which is worth knowing — the first is what to
 * stop holding, the last is where the object that leaked hangs off — and a row of a list is read left to
 * right, so it is the same order the chain is in. See [LeakGroup.suspectPath] and [NAME_HINT].
 *
 * It ends on an arrow, because what the last reference points at is the row underneath: the references are
 * the leak and the objects below them are what it left behind, which is the whole shape of this list.
 */
private fun LeakGroup.nameText(): String = when (suspectPath.size) {
  // A library leak is named by the pattern that recognized it and an unreachable one by its class, and
  // neither is a reference, so neither points anywhere.
  0 -> title
  1 -> "${suspectPath.single()} $STRETCH_ARROW"
  2 -> "${suspectPath.first()} $STRETCH_ARROW ${suspectPath.last()} $STRETCH_ARROW"
  else ->
    "${suspectPath.first()} $STRETCH_ARROW $STRETCH_GAP $STRETCH_ARROW ${suspectPath.last()} $STRETCH_ARROW"
}

/** How many objects the leak has past the one on the row above, which is what opening it shows. */
private fun moreObjectsText(count: Int): String =
  "$count $MORE ${if (count == 1) OBJECT else OBJECTS} $LEAKING_THE_SAME_WAY"

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

/** What the row opening the rest of a leak says, since a bare count would read as a count of leaks. */
internal const val MORE = "more"
internal const val LEAKING_THE_SAME_WAY = "leaking the same way"

/** Its key in the list, which is one per leak and so can't be an object id. */
private const val MORE_KEY = "more"

/** What the line about the watcher starts with, so it reads as the watcher's line and not the object's. */
private const val WATCHED_GLYPH = "◉"

/** What the hash of a leak is called on the row, since a bare 40 characters of hex names nothing. */
internal const val SIGNATURE = "Signature:"

internal const val NAME_HINT =
  "The references this leak is: the first is the one that shouldn't be holding any more, which is what " +
    "LeakCanary calls the leak, and the last is the one that points straight at what leaked, which is " +
    "where to look on the chain to see it. They are one reference for most leaks. Everything above the " +
    "first is the app working as intended and everything below the last is what the leak is holding, so " +
    "neither is part of what makes this leak this leak."

internal const val SIGNATURE_HINT =
  "A hash of how this leak is held, which is the same for the same leak in the next heap dump of this app " +
    "— unlike the addresses under it, which are of this one. So it is what to write in a bug report, and " +
    "what to compare two dumps by. It is also the signature LeakCanary prints under this leak when it " +
    "reports it, so a report and this list can be lined up hash by hash."

/** Between the two ends of a leak, pointing the way the chain runs: down, away from the GC roots. */
internal const val STRETCH_ARROW = "→"

/** And what stands in for the references between them, which are on the chain and not on the row. */
internal const val STRETCH_GAP = "…"

internal const val FOLDED_ARROW = "▸"
internal const val EXPANDED_ARROW = "▾"

/** Wide enough for the triangle on the one row of a leak that opens and closes. */
private val TOGGLE_WIDTH = 16.dp

/** Between the last leak of one part and the heading of the next, which is what parts the three. */
private val SECTION_GAP = 20.dp

/** And the line across that gap, heavier than the one under the count so that it reads as a break. */
private val SECTION_RULE_WIDTH = 2.dp
private val SECTION_RULE_COLOR = Color(0x33000000)

/** The bar tying a part's rows to its heading. Wide enough to read as a bar and not as a window edge. */
private val SECTION_BAR_WIDTH = 6.dp

/** Enough that the objects of a leak read as being inside it rather than as more leaks. */
private val INSTANCE_INSET = 28.dp

/** The shade behind them, faint enough to leave the rows on it readable and the leak rows the brighter. */
private val INSTANCE_BACKGROUND = Color(0x0D000000)

/** And the rule down their left, which is what ties them to the leak above rather than to each other. */
private val INSTANCE_RULE_COLOR = Color(0x33000000)
private val INSTANCE_RULE_WIDTH = 2.dp

/** How far short of the bottom the rule stops on the last object, which is where the leak ends. */
private val INSTANCE_RULE_TAIL = 6.dp

/** Wide enough for a size in gigabytes, so that the numbers line up down the column. */
private val SIZE_COLUMN_WIDTH = 72.dp

/** A library leak's description is a paragraph, and a list of leaks has room for the start of it. */
private const val MAX_SUBTITLE_LINES = 3

private val SPINNER_SIZE = 12.dp
private val SPINNER_STROKE = 2.dp
