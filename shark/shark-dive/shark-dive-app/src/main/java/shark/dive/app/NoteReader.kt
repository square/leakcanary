package shark.dive.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import shark.dive.Note
import shark.dive.NoteBlock
import shark.dive.NoteLink
import shark.dive.NoteMentions
import shark.dive.NoteReferences
import shark.dive.NoteSpan

/**
 * How a window reads the reasons written into it: the reason behind a verdict, and the reason an agent gave
 * for a call.
 *
 * A reason is read the way a note is, as markdown with the class names and addresses in it leading into this
 * heap dump. Whoever writes why an object is stuck names the object holding it by its address, the way they
 * would in a note.
 *
 * **What a name stands for is asked once per window and kept.** The same reason is drawn several times over:
 * in the panel, on every step of a path through the object, in the row of its leak. A class name is the same
 * class and an address the same object for as long as the heap dump is open, so nothing kept here goes stale.
 * The asking happens on the composition's thread and the read on the heap dump's, like every read. The answer
 * is written back on the composition's thread, so no second thread ever reads what is kept.
 *
 * **The read runs in the window's scope, not the scope of the reason that asked.** A reason scrolled off a
 * list leaves the composition while its read is still queued. Cancelling that read would leave its names
 * marked as asked and never answered, for every other reason that mentions them.
 */
internal class NoteReader(
  /** Where clicking a link in a reason goes. See [HeapDumpDive]. */
  val onLink: (NoteLink) -> Unit,
  private val scope: CoroutineScope,
  /** What the heap dump has for [NoteMentions], which is a read of it. See [shark.dive.referencesOf]. */
  private val read: suspend (NoteMentions) -> NoteReferences
) {

  /** What every name asked about so far turned out to be, which is what a reason is resolved with. */
  var references: NoteReferences by mutableStateOf(NoteReferences.NONE)
    private set

  private val askedClassNames = mutableSetOf<String>()
  private val askedObjectIds = mutableSetOf<Long>()

  /** Asks the heap dump about whatever in [mentions] it hasn't been asked about yet, and nothing else. */
  fun ask(mentions: NoteMentions) {
    val unasked = NoteMentions(
      classNames = mentions.classNames - askedClassNames,
      objectIds = mentions.objectIds - askedObjectIds
    )
    if (unasked.isEmpty) {
      return
    }
    askedClassNames += unasked.classNames
    askedObjectIds += unasked.objectIds
    scope.launch {
      val learnt = read(unasked)
      references = NoteReferences(
        classObjectIds = references.classObjectIds + learnt.classObjectIds,
        objectNames = references.objectNames + learnt.objectNames
      )
    }
  }
}

/**
 * A reason, drawn the way a note is. See [NoteReader].
 *
 * [lead] is what the window puts in front of it, such as the verdict a reason is for or the word an agent's
 * reason is introduced with. It goes on the reason's first line in the same style, so a reason of one line is
 * still drawn as one line.
 */
@Composable
internal fun ReasonText(
  reason: String,
  reader: NoteReader,
  style: TextStyle,
  color: Color,
  lead: String = "",
  /**
   * False for a reason about another heap dump. Its addresses are objects of that dump, and this one has
   * nothing to say about them, or worse, has an unrelated object at the same address.
   */
  isAboutThisHeapDump: Boolean = true
) {
  val note = remember(reason) { Note.of(reason) }
  if (isAboutThisHeapDump) {
    LaunchedEffect(reader, note) { reader.ask(note.mentions) }
  }
  val resolved = if (isAboutThisHeapDump) note.resolvedWith(reader.references) else note
  Column {
    resolved.blocks.ledBy(lead).forEach { block ->
      NoteBlockView(block, reader.onLink, style, color)
    }
  }
}

/**
 * The same blocks with [lead] in front of the first one, or above it when that one is not a line of prose:
 * `Stuck: - a bullet` would turn the list item into text that starts with a dash.
 */
private fun List<NoteBlock>.ledBy(lead: String): List<NoteBlock> {
  if (lead.isEmpty()) {
    return this
  }
  val first = firstOrNull()
  if (first is NoteBlock.Paragraph) {
    return listOf(NoteBlock.Paragraph(listOf(NoteSpan(lead)) + first.spans)) + drop(1)
  }
  return listOf(NoteBlock.Paragraph(listOf(NoteSpan(lead.trimEnd())))) + this
}
