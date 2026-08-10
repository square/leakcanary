package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import shark.explorer.NoteBlock
import shark.explorer.NoteLink
import shark.explorer.NoteSpan
import shark.explorer.NoteStyle
import shark.explorer.hexObjectId

/**
 * What has been written about the tab on screen, between the row that says where the tab is and the panes
 * that read it.
 *
 * There rather than at the foot of the window, because a note is about the whole of what the tab is showing:
 * under the title it is about, above everything it describes. At the bottom it would read as a note on
 * whichever pane happened to be above it.
 *
 * **Not there at all until there is something to show**, which is what keeps a section that is on every tab
 * from taking room it has not earned: most tabs are never written about, and the way to start one is
 * [AddNoteButton] in the row above. So there are two states here rather than three:
 *
 * - **Writing**: a plain text box with save and cancel. Plain, because markdown is what gets typed here and
 *   a box that reformats it as you go is a box arguing with you.
 * - **Written**: the note as it means, which is where the names in it lead somewhere — a class shortened to
 *   a link, an address replaced by what is at it, a `shark://` link back to the tab it was copied from.
 *
 * One markdown file per place, kept between runs, read by the window rather than here. See [PlaceNotes].
 */
@Composable
internal fun NoteSection(
  notes: PlaceNotes,
  /** Where clicking a link in the written note goes. See [HeapDumpExplorer]. */
  onLink: (NoteLink) -> Unit,
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
          onSave = { saving.launch { notes.save() } },
          onCancel = { notes.cancel() }
        )
        notes.text.isNotEmpty() -> WrittenNote(
          notes = notes,
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
      HorizontalDivider()
    }
  }
}

/**
 * The button that starts a note, for the row that says where the tab is.
 *
 * In that row rather than in the section, and gone as soon as there is a note, so that a tab nobody has
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
    TextButton(onClick = { notes.edit() }, modifier = modifier, enabled = notes.isRead) {
      Text(NOTE_BUTTON, style = MaterialTheme.typography.bodySmall)
    }
  }
}

/** The note as it means, with the way back into the box that wrote it. */
@Composable
private fun WrittenNote(
  notes: PlaceNotes,
  onLink: (NoteLink) -> Unit,
  onEdit: () -> Unit
) {
  Row(
    Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top
  ) {
    Column(
      Modifier.weight(1f).heightIn(max = MAX_NOTE_HEIGHT)
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
  onSave: () -> Unit,
  onCancel: () -> Unit
) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
    OutlinedTextField(
      value = draft,
      onValueChange = { notes.edited(it) },
      placeholder = { Text(NOTE_PLACEHOLDER, style = MaterialTheme.typography.bodySmall) },
      textStyle = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.fillMaxWidth().height(EDITOR_HEIGHT)
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

@Composable
private fun NoteBlockView(
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

/** What starts a note about this tab, in the row that says where the tab is. See [AddNoteButton]. */
internal const val NOTE_BUTTON = "✎ Add Note"

/** And what opens the one that is already there. */
internal const val EDIT_NOTE_BUTTON = "✎ Edit"

internal const val SAVE_NOTE = "Save"

internal const val CANCEL_NOTE = "Cancel"

/** What marks a tab that has a note, in front of its title on the strip. */
internal const val NOTE_MARK = "✎"

internal const val NOTE_MARK_HINT = "This tab has a note."

/** What the editor is called, which is also how a test finds it: there is no other text field here. */
internal const val NOTE_EDITOR_DESCRIPTION = "The note about this tab, as markdown."

private const val WRITE_NOTE_HINT =
  "Write a note about this tab, in markdown, kept between runs. A class name, an address like 0x1234 and a " +
    "shark:// link to a tab all turn into a way back into this window, and http links open in a browser."

private const val EDIT_NOTE_HINT = "Change what this note says."

private const val NOTE_PLACEHOLDER =
  "Markdown. Class names, 0x addresses and shark:// links become links into this heap dump."

private const val SAVED_IN = "Saved in"

/** In front of a quoted line, since a quote here is one line rather than a paragraph to draw a bar beside. */
private const val QUOTE_BAR = "▎"

private val BLOCK_SPACING = 4.dp

/** How far a list under a list is drawn in, and how much room the bullet or the number gets. */
private val INDENT_WIDTH = 16.dp
private val MARKER_WIDTH = 24.dp

/** Enough for a few lines while writing, so that the panes under it keep the window. */
private val EDITOR_HEIGHT = 120.dp

/** And how much of it a long note gets before it scrolls instead of pushing the panes down. */
private val MAX_NOTE_HEIGHT = 160.dp
