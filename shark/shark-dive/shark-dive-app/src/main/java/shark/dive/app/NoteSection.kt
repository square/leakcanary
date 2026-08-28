package shark.dive.app

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import shark.dive.NoteBlock
import shark.dive.NoteLink
import shark.dive.NoteSpan
import shark.dive.NoteStyle
import shark.dive.hexObjectId

/**
 * What has been written about where the tab is, between the row that says so and the panes that read it.
 *
 * There rather than at the foot of the window, because a note is about the whole of what the tab is showing:
 * under the title it is about, above everything it describes. At the bottom it would read as a note on
 * whichever pane happened to be above it.
 *
 * **A note belongs to the place, not to the tab**, so two tabs on one place are one note and show each other's
 * writing as it is typed — `Place.noteKey` and [PlaceNotes] are the two halves of that. A place here is a
 * location in the heap dump rather than an object: an object, a group of smaller ones, the object list, the
 * leaks, the starred objects.
 *
 * **Not there at all until there is something to show**, which is what keeps a section that is on every tab
 * from taking room it has not earned: most places are never written about, and the way to start one is
 * [AddNoteButton] under the title. So there are two states here rather than three:
 *
 * - **Writing**: a plain text box with save and cancel. Plain, because markdown is what gets typed here and
 *   a box that reformats it as you go is a box arguing with you.
 * - **Written**: the note as it means, which is where the names in it lead somewhere — a class shortened to
 *   a link, an address replaced by what is at it, a `shark://` link back to the tab it was copied from.
 *
 * **The line along the bottom is the same divider the panes are resized by**, dragged up and down instead:
 * how much of the window a note is worth is the reading of it against the reading of the heap dump, and that
 * changes with the note. How tall it has been dragged to is [PanesState.noteHeight], per window.
 *
 * One markdown file per place, kept between runs, read by the window rather than here. See [PlaceNotes].
 */
@Composable
internal fun NoteSection(
  notes: PlaceNotes,
  /** Where clicking a link in the written note goes. See [HeapDumpDive]. */
  onLink: (NoteLink) -> Unit,
  /** How tall it has been dragged to, and where a drag of its bottom edge goes. See [PanesState]. */
  height: Dp,
  onResize: (Dp) -> Unit,
  modifier: Modifier = Modifier
) {
  val draft = notes.draft
  val problem = notes.problem
  if (draft == null && notes.text.isEmpty() && problem == null) {
    return
  }
  val saving = rememberCoroutineScope()

  Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxWidth()) {
      when {
        draft != null -> NoteEditor(
          notes = notes,
          draft = draft,
          height = height,
          onSave = { saving.launch { notes.save() } },
          onCancel = { notes.cancel() }
        )
        notes.text.isNotEmpty() -> WrittenNote(
          notes = notes,
          height = height,
          onLink = onLink,
          onEdit = { notes.edit() }
        )
        // Only a problem to report, which is a note that could not be read: the section is the one place
        // that can say so, since the button that would have started one is disabled until the read lands.
      }
      if (problem != null) {
        Text(
          problem,
          Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error
        )
      }
      // Where a `HorizontalDivider` would be, and the same line to look at: the note's bottom edge is the
      // one place a drag of it can be, since it is the only edge of the section that isn't the title above.
      PaneDivider(RESIZE_NOTE_HINT, Orientation.Vertical, onResize)
    }
  }
}

/**
 * The button that starts a note, drawn under the title that says what the tab is on.
 *
 * Under it rather than in the section, and gone as soon as there is a note, so that a place nobody has
 * written about spends no window on saying so. A note carries its own way back into the box — see
 * [WrittenNote] — which is why there is never one of each on screen.
 *
 * Disabled until the file has been read, which is the one moment writing would be dangerous: an empty box
 * over a note still on its way off the disk is that note deleted a save later.
 */
@Composable
internal fun AddNoteButton(
  notes: PlaceNotes,
  modifier: Modifier = Modifier
) {
  if (notes.text.isNotEmpty() || notes.draft != null) {
    return
  }
  Hint(WRITE_NOTE_HINT) {
    // Small, and with no padding of its own around the label, because it hangs under the title on every tab
    // that has no note: what it costs there should be a line of window rather than a row of one, and it
    // should start where the title above it starts.
    TextButton(
      onClick = { notes.edit() },
      modifier = modifier.height(ADD_NOTE_HEIGHT),
      enabled = notes.isRead,
      contentPadding = PaddingValues(horizontal = 0.dp)
    ) {
      Text(NOTE_BUTTON, style = MaterialTheme.typography.bodySmall)
    }
  }
}

/** The note as it means, with the way back into the box that wrote it. */
@Composable
private fun WrittenNote(
  notes: PlaceNotes,
  height: Dp,
  onLink: (NoteLink) -> Unit,
  onEdit: () -> Unit
) {
  Row(
    Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top
  ) {
    Column(
      Modifier.weight(1f).noteHeight(height, fill = false)
        .verticalScroll(rememberScrollState())
        .padding(vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(BLOCK_SPACING)
    ) {
      notes.note.blocks.forEach { block ->
        NoteBlockView(block, onLink)
      }
    }
    Hint(EDIT_NOTE_HINT) {
      TextButton(onClick = onEdit) {
        Text(EDIT_NOTE_BUTTON, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

/**
 * The markdown as it is typed, with what saving and cancelling mean.
 *
 * Nothing is written until save, and cancel throws the typing away rather than filing it: a box with two
 * buttons under it is a promise about which of those two happens. The draft outlives leaving the tab, so
 * clicking somewhere else while half way through a sentence is not losing it — see [PlaceNotes.draft].
 */
@Composable
private fun NoteEditor(
  notes: PlaceNotes,
  draft: String,
  height: Dp,
  onSave: () -> Unit,
  onCancel: () -> Unit
) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
    OutlinedTextField(
      value = draft,
      onValueChange = { notes.edited(it) },
      placeholder = { Text(NOTE_PLACEHOLDER, style = MaterialTheme.typography.bodySmall) },
      textStyle = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.fillMaxWidth().noteHeight(height, fill = true)
        .semantics { contentDescription = NOTE_EDITOR_DESCRIPTION }
    )
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextButton(onClick = onSave) {
        Text(SAVE_NOTE, style = MaterialTheme.typography.bodySmall)
      }
      TextButton(onClick = onCancel) {
        Text(CANCEL_NOTE, style = MaterialTheme.typography.bodySmall)
      }
      // Where it is going to land, which is what someone wanting to open this note in an editor needs and
      // is worth the room only while they are looking at the box that writes it.
      Text(
        "$SAVED_IN ${notes.file}",
        Modifier.weight(1f),
        style = MaterialTheme.typography.bodySmall,
        color = MUTED_TEXT,
        maxLines = 1
      )
    }
  }
}

/**
 * As tall as the divider has been dragged to, and never more than [NOTE_SHARE] of the room the tab has.
 *
 * The share is what keeps the divider reachable: a note dragged tall and then a window made short would
 * otherwise place its own bottom edge past the bottom of the screen, and the only way back would be to make
 * the window bigger again.
 *
 * [fill] is the difference between the box being typed in, which is that tall whether or not anything has
 * been typed yet, and the note as it reads, which takes what it needs up to that and then scrolls.
 */
private fun Modifier.noteHeight(
  height: Dp,
  fill: Boolean
) = layout { measurable, constraints ->
  val most = if (constraints.hasBoundedHeight) {
    minOf(height.roundToPx(), (constraints.maxHeight * NOTE_SHARE).roundToInt())
  } else {
    height.roundToPx()
  }
  val placeable = measurable.measure(
    constraints.copy(minHeight = if (fill) most else 0, maxHeight = most)
  )
  layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** One block of markdown, whether it was typed as a note or shipped as a page. See [ReferenceScreen]. */
@Composable
internal fun NoteBlockView(
  block: NoteBlock,
  onLink: (NoteLink) -> Unit
) {
  when (block) {
    is NoteBlock.Paragraph -> NoteText(block.spans, MaterialTheme.typography.bodyMedium, onLink)
    is NoteBlock.Heading -> NoteText(block.spans, headingStyle(block.level), onLink)
    is NoteBlock.Item -> Row(Modifier.padding(start = INDENT_WIDTH * block.depth)) {
      Text(
        block.marker,
        Modifier.width(MARKER_WIDTH),
        style = MaterialTheme.typography.bodyMedium,
        color = MUTED_TEXT
      )
      NoteText(block.spans, MaterialTheme.typography.bodyMedium, onLink)
    }
    is NoteBlock.Quote -> Row {
      Text(QUOTE_BAR, style = MaterialTheme.typography.bodyMedium, color = MUTED_TEXT)
      NoteText(block.spans, MaterialTheme.typography.bodyMedium, onLink, color = MUTED_TEXT)
    }
    is NoteBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
      Text(
        block.text,
        Modifier.padding(8.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
      )
    }
    NoteBlock.Rule -> HorizontalDivider()
  }
}

/**
 * One block's worth of styled text, with a click handler on every span that leads somewhere.
 *
 * Through [LinkAnnotation], which is what makes part of a line clickable at all: a `Text` is one node, so a
 * link inside a sentence cannot be a composable of its own.
 */
@Composable
private fun NoteText(
  spans: List<NoteSpan>,
  style: TextStyle,
  onLink: (NoteLink) -> Unit,
  color: Color = Color.Unspecified
) {
  Text(annotatedNote(spans, onLink), style = style, color = color)
}

private fun annotatedNote(
  spans: List<NoteSpan>,
  onLink: (NoteLink) -> Unit
): AnnotatedString = buildAnnotatedString {
  spans.forEach { span ->
    val link = span.link
    if (link == null) {
      withStyle(span.spanStyle()) { append(span.text) }
    } else {
      withLink(
        LinkAnnotation.Clickable(
          tag = link.tag(),
          styles = TextLinkStyles(SpanStyle(color = LINK_COLOR)),
          linkInteractionListener = { onLink(link) }
        )
      ) {
        withStyle(span.spanStyle()) { append(span.text) }
      }
    }
  }
}

private fun NoteSpan.spanStyle(): SpanStyle = SpanStyle(
  fontWeight = if (NoteStyle.BOLD in styles) FontWeight.Bold else null,
  fontStyle = if (NoteStyle.ITALIC in styles) FontStyle.Italic else null,
  fontFamily = if (NoteStyle.CODE in styles) FontFamily.Monospace else null
)

/** What a link is called where something other than a person reads it: a screen reader, or a test. */
private fun NoteLink.tag(): String = when (this) {
  is NoteLink.Web -> url
  is NoteLink.Deep -> deepLink.toUri()
  is NoteLink.Object -> hexObjectId(objectId)
}

/**
 * A heading has to be bigger than the line under it or it isn't one: `titleSmall` beside `bodyMedium` is the
 * same size in a slightly heavier weight, which reads as a bold line rather than as a heading.
 */
@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
  1 -> MaterialTheme.typography.headlineSmall
  2 -> MaterialTheme.typography.titleLarge
  else -> MaterialTheme.typography.titleMedium
}

/** What starts a note about what the tab is on, under the title saying what that is. See [AddNoteButton]. */
internal const val NOTE_BUTTON = "✎ Add Note"

/** And what opens the one that is already there. */
internal const val EDIT_NOTE_BUTTON = "✎ Edit"

internal const val SAVE_NOTE = "Save"

internal const val CANCEL_NOTE = "Cancel"

/** What marks a tab that has a note, in front of its title on the strip. */
internal const val NOTE_MARK = "✎"

internal const val NOTE_MARK_HINT = "There is a note about what this tab is on."

/** What the editor is called, which is also how a test finds it: there is no other text field here. */
internal const val NOTE_EDITOR_DESCRIPTION = "The note about what this tab is on, as markdown."

/**
 * Short, because what a note can do is the box's business rather than this button's: a tooltip is read while
 * deciding whether to click, and by then everything about markdown and links is a paragraph in the way. It is
 * in [NOTE_PLACEHOLDER] instead, which is on screen exactly while it is worth reading.
 */
private const val WRITE_NOTE_HINT = "Write a note about what this tab is on, kept between runs."

private const val EDIT_NOTE_HINT = "Change what this note says."

private const val NOTE_PLACEHOLDER =
  "Markdown. Class names, 0x addresses and shark:// links become links into this heap dump, and http links " +
    "open in a browser."

private const val SAVED_IN = "Saved in"

/** What dragging the note's bottom edge does, said where a bar of pixels can't say it. See [PaneDivider]. */
internal const val RESIZE_NOTE_HINT = "Drag to make the note taller or shorter."

/** In front of a quoted line, since a quote here is one line rather than a paragraph to draw a bar beside. */
private const val QUOTE_BAR = "▎"

private val BLOCK_SPACING = 4.dp

/** How far a list under a list is drawn in, and how much room the bullet or the number gets. */
private val INDENT_WIDTH = 16.dp
private val MARKER_WIDTH = 24.dp

/** A line under the title rather than a button beside it. See [AddNoteButton]. */
private val ADD_NOTE_HEIGHT = 20.dp

/** Whatever the divider says, this much of the tab's height is the most a note gets. See [noteHeight]. */
private const val NOTE_SHARE = 0.6f
