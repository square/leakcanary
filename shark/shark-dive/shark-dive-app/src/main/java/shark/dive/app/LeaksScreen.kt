package shark.dive.app

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
import androidx.compose.foundation.lazy.LazyListScope
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
import shark.dive.HeapLeaks
import shark.dive.LeakGroup
import shark.dive.LeakKind
import shark.dive.LeakSection
import shark.dive.LeakingObject
import shark.dive.Note
import shark.dive.NoteLink
import shark.dive.Place
import shark.dive.Topic
import shark.dive.WatchedObject
import shark.dive.formatByteSize
import shark.dive.hexObjectId

/**
 * Every leaking object of the heap dump, in two halves: the leaks to do something about, which is the app's
 * own and the ones in code it doesn't control, and under one folded heading the objects that shouldn't be in
 * memory and are already leaving it.
 *
 * One row per leak rather than per object, because a leak with fifty instances is one thing to fix; the row
 * unfolds into them when there is more than one. Every row leads into the object view — a leak is a
 * chain from a GC root, and the chain the map already draws is exactly that, so there is no leak trace here
 * and nothing to read twice.
 */
@Composable
internal fun LeaksScreen(
  leaks: HeapLeaks,
  isFindingLeaks: Boolean,
  expandedGroups: Set<String>,
  onToggleGroup: (String) -> Unit,
  onOpen: (Long, OpenIn) -> Unit,
  /** Puts a link to a row's object on the clipboard, beside opening it. See [OpenTarget]. */
  onCopyLink: (Long) -> Unit,
  /** Where the `?` beside what a leak is called goes. See [Explain]. */
  onExplain: (Topic) -> Unit,
  /** And where a link written into a library leak's description goes, which is out to a browser. */
  onFollowLink: (NoteLink) -> Unit,
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
        leaks.leakSections.forEachIndexed { sectionIndex, section ->
          leakSection(
              section,
              sectionIndex == 0,
              expandedGroups,
              onToggleGroup,
              onOpen,
              onCopyLink,
              onExplain,
              onFollowLink
            )
        }
        val onTheWayOut = leaks.onTheWayOutSections
        val isOnTheWayOutExpanded = Place.Leaks.ON_THE_WAY_OUT in expandedGroups
        // Not pinned, unlike the headings under it: what a heading is pinned for is the rows scrolling under
        // it, and there are none of its own — it is a heading over headings, and each of those pins itself
        // as it comes up.
        item(key = Place.Leaks.ON_THE_WAY_OUT) {
          OnTheWayOutHeader(
            sections = onTheWayOut,
            isExpanded = isOnTheWayOutExpanded,
            onToggle = { onToggleGroup(Place.Leaks.ON_THE_WAY_OUT) }
          )
        }
        if (isOnTheWayOutExpanded) {
          onTheWayOut.forEachIndexed { sectionIndex, section ->
            leakSection(
              section,
              sectionIndex == 0,
              expandedGroups,
              onToggleGroup,
              onOpen,
              onCopyLink,
              onExplain,
              onFollowLink
            )
          }
        }
      }
    }
  }
}

/** One part of the list: its heading, and a leak of it per row with the objects of that leak inside it. */
private fun LazyListScope.leakSection(
  section: LeakSection,
  /** Whether it opens whatever it is under, where there is nothing above it to be told apart from. */
  isFirst: Boolean,
  expandedGroups: Set<String>,
  onToggleGroup: (String) -> Unit,
  onOpen: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit,
  onExplain: (Topic) -> Unit,
  onFollowLink: (NoteLink) -> Unit
) {
  // Pinned while its own leaks scroll under it, which is what says they are its: a heading that scrolls away
  // leaves a list of rows with nothing above them saying which part they are.
  stickyHeader(key = section.kind.name) {
    SectionHeader(section, isFirst = isFirst, onExplain = onExplain)
  }
  section.groups.forEach { group ->
    val groupKey = section.kind.groupKey(group)
    val isExpanded = groupKey in expandedGroups
    val hasMore = group.objects.size > 1
    item(key = groupKey) { GroupRow(group, onExplain, onFollowLink) }
    // The first object always: a leak is a reference, and a reference with nothing under it says
    // what shouldn't be holding without ever saying what it is holding.
    item(key = "$groupKey ${group.objects.first().objectId}") {
      LeakingObjectRow(
        leakingObject = group.objects.first(),
        isLast = !hasMore,
        onOpen = onOpen,
        onCopyLink = onCopyLink
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
              onOpen = onOpen,
              onCopyLink = onCopyLink
            )
          }
        }
      }
    }
  }
}

/**
 * What one part of the list is, and how much of the heap dump is in it.
 *
 * The gap and the rule are above it and nothing is below it, which is the whole of what says the leaks
 * under it are its: a heading the same distance from the section above and the one below belongs to
 * neither. The band is opaque for the same reason the header is pinned — rows scroll under it.
 */
@Composable
private fun SectionHeader(
  section: LeakSection,
  /** Whether it opens what it is under, where there is nothing above to be told apart from. */
  isFirst: Boolean,
  onExplain: (Topic) -> Unit
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
      // What to do about a library leak is a page rather than a line, and it is the one section where what
      // to do isn't obvious from what the section is. See [LeakKind.topic].
      val topic = section.kind.topic
      if (topic == null) {
        SectionExplanation(section.kind.explanation, Modifier)
      } else {
        Explain(topic, onExplain) {
          // The weight leaves the `?` its room: a paragraph in a row takes the whole width otherwise, and
          // the `?` is pushed off the end of it.
          SectionExplanation(section.kind.explanation, Modifier.weight(1f, fill = false))
        }
      }
    }
  }
}

/** What being in a section means, which not one of the titles says on its own. See [LeakKind]. */
@Composable
private fun SectionExplanation(
  explanation: String,
  modifier: Modifier
) {
  Text(explanation, modifier, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
}

/**
 * The heading over the half of the list nobody has to act on, and the one thing on this screen that hides
 * rows rather than showing them.
 *
 * Folded to start with, and quieter than a section heading — no band, no bar down its rows, muted text —
 * because the answer these sections give is that there is nothing to do: a screen that draws them like the
 * app's own leaks says a heap dump with nothing wrong in it has five more kinds of problem. What it does say
 * while folded is how many objects of each kind are in there, so that folding hides the rows and never the
 * answer, and pressing it is what asks why they are still in memory.
 */
@Composable
private fun OnTheWayOutHeader(
  sections: List<LeakSection>,
  isExpanded: Boolean,
  onToggle: () -> Unit
) {
  Column(Modifier.fillMaxWidth().padding(top = SECTION_GAP)) {
    HorizontalDivider(thickness = SECTION_RULE_WIDTH, color = SECTION_RULE_COLOR)
    Column(
      Modifier.fillMaxWidth()
        .clickableRow(onClick = onToggle)
        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          if (isExpanded) EXPANDED_ARROW else FOLDED_ARROW,
          Modifier.width(TOGGLE_WIDTH),
          style = MaterialTheme.typography.titleSmall,
          color = MUTED_TEXT,
          textAlign = TextAlign.Center
        )
        Text(
          "${LeakKind.ON_THE_WAY_OUT_TITLE} · ${sections.summaryByKind()}",
          style = MaterialTheme.typography.titleSmall,
          color = MUTED_TEXT
        )
      }
      // Only once it is open, since a paragraph is attention and what these sections are is not worth any
      // until someone has asked for them.
      if (isExpanded) {
        Text(
          LeakKind.ON_THE_WAY_OUT_EXPLANATION,
          Modifier.padding(start = TOGGLE_WIDTH + 4.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MUTED_TEXT
        )
      }
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
private fun GroupRow(
  group: LeakGroup,
  onExplain: (Topic) -> Unit,
  onFollowLink: (NoteLink) -> Unit
) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    SectionBar()
    Row(
      Modifier.weight(1f).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Explain(Topic.LEAK_NAME, onExplain) {
          Text(
            group.nameText(),
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        group.subtitle?.let { subtitle ->
          // In full, and with the links live. What Shark knows about a library leak is written into the
          // pattern that recognizes it, and half of those descriptions end in the AOSP change that
          // introduced the leak or the file it is in — which is where the way round it is, and which three
          // lines of ellipsized plain text was throwing away. See [Note.ofDocument].
          Note.ofDocument(subtitle).blocks.forEach { block ->
            NoteBlockView(block, onFollowLink, MaterialTheme.typography.bodySmall, MUTED_TEXT)
          }
        }
        // What makes a leak something to write down: the addresses in this list are of one heap dump, and
        // this is the same for the same leak in the next one. See [LeakGroup.leakFingerprint].
        Explain(Topic.LEAK_FINGERPRINT, onExplain) {
          Text(
            "$LEAK_FINGERPRINT ${group.leakFingerprint}",
            Modifier.weight(1f, fill = false),
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
      Modifier.weight(1f).clickableRow(onClick = onToggle)
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
  onOpen: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit
) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(INSTANCE_BACKGROUND)) {
    SectionBar()
    Spacer(Modifier.width(INSTANCE_INSET - SECTION_BAR_WIDTH))
    InstanceRule(isLast)
    Column(Modifier.weight(1f).padding(start = 8.dp, end = 12.dp, bottom = 4.dp)) {
      val open: (OpenIn) -> Unit = { openIn -> onOpen(leakingObject.objectId, openIn) }
      OpenTarget(open, { onCopyLink(leakingObject.objectId) }) {
        Row(
          Modifier.fillMaxWidth().openable(open).padding(vertical = 2.dp),
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
            // Why *this* object is stuck, which the inspector that recognized it read off the object
            // itself: two objects of one leak can be stuck for reasons that don't read the same.
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
      }
      leakingObject.watcher?.let { watcher ->
        WatcherRow(
          watcher = watcher,
          alreadySaid = leakingObject.leakingReason.orEmpty(),
          onOpen = onOpen,
          onCopyLink = onCopyLink
        )
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
  onOpen: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit
) {
  val objectId = watcher.weakReferenceObjectId
  val open: (OpenIn) -> Unit = { openIn -> onOpen(objectId, openIn) }
  OpenTarget(open, { onCopyLink(objectId) }) {
    Column(Modifier.fillMaxWidth().openable(open).padding(bottom = 2.dp)) {
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
}

/** Which section a group is in as well as which group it is: two sections can hold the same title. */
private fun LeakKind.groupKey(group: LeakGroup): String = "$name ${group.leakFingerprint}"

/** How many leaks and how many objects, which is what says a leak is one thing and not fifty. */
private fun LeakSection.summary(): String = when {
  groups.isEmpty() -> NONE_FOUND
  else -> "${groups.size} ${if (groups.size == 1) LEAK else LEAKS}, " +
    "$objectCount ${if (objectCount == 1) OBJECT else OBJECTS}"
}

/**
 * What is in the folded half, per section: `3 phantom reachable, 2 unreachable`.
 *
 * Which sections rather than a count of the lot, because the sections are the answer — an object waiting to
 * be finalized and one nothing reaches at all are both leaving, and only one of them has run any code yet.
 */
private fun List<LeakSection>.summaryByKind(): String =
  filter { it.objectCount > 0 }
    .joinToString { "${it.objectCount} ${it.kind.title.lowercase()}" }
    .ifEmpty { NONE_FOUND }

/**
 * How many leaks there are to do something about, and then how many objects are on their way out.
 *
 * The two counts are kept apart for the same reason the list is: a dump whose leaks have all been collected
 * is a dump with nothing to fix, and one number over both halves says the opposite.
 */
private fun HeapLeaks.countText(): String {
  val leaking = when (leakingObjectCount) {
    0 -> NOTHING_LEAKING
    else -> "$leakingObjectCount leaking ${if (leakingObjectCount == 1) OBJECT else OBJECTS}"
  }
  val onTheWayOut = onTheWayOutSections.sumOf { it.objectCount }
  return when (onTheWayOut) {
    0 -> leaking
    else -> "$leaking · $onTheWayOut ${LeakKind.ON_THE_WAY_OUT_TITLE.lowercase()}"
  }
}

private fun LeakGroup.objectCountText(): String =
  "${objects.size} ${if (objects.size == 1) OBJECT else OBJECTS}"

/**
 * Both ends of what the leak is, and a gap for whatever is between them.
 *
 * One line rather than two, because the ends are the same reference for most leaks and a second line that
 * repeats the first says the row has two names. Which end is which is worth knowing — the first is what to
 * stop holding, the last is where the object that leaked hangs off — and a row of a list is read left to
 * right, so it is the same order the chain is in. See [LeakGroup.suspectPath] and the `leak-name` page of the reference.
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

/** No full stop, since what is on their way out is said after it on the same line. */
private const val NOTHING_LEAKING = "Nothing in this heap dump is stuck"
private const val NONE_FOUND = "none"

private const val LEAK = "leak"
private const val LEAKS = "leaks"
private const val OBJECT = "object"
private const val OBJECTS = "objects"

/** What the row opening the rest of a leak says, since a bare count would read as a count of leaks. */
internal const val MORE = "more"
internal const val LEAKING_THE_SAME_WAY = "stuck the same way"

/** Its key in the list, which is one per leak and so can't be an object id. */
private const val MORE_KEY = "more"

/** What the line about the watcher starts with, so it reads as the watcher's line and not the object's. */
private const val WATCHED_GLYPH = "◉"

/** What the hash of a leak is called on the row, since a bare 40 characters of hex names nothing. */
internal const val LEAK_FINGERPRINT = "Leak fingerprint:"

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
