package shark.dive

/**
 * What someone has written about one place in a heap dump, as blocks to draw.
 *
 * The point of it is that a note about a heap dump is mostly made of names out of that heap dump — a class,
 * an address, a link to a tab — and typing those out again as prose is what makes notes not worth keeping.
 * So the markdown a reader types is read here, and everything in it that this heap dump recognises becomes
 * a way back into the window: a class name links to that class, an address links to that object, a
 * `shark://` link opens the tab it was copied from. See [NoteMention].
 *
 * Immutable and in this module rather than in the UI, so that what a note means is unit tested rather than
 * found out by typing one. Rendering it is `NoteSection`, and where it is kept is [NoteDirectory].
 *
 * The markdown is a subset, and deliberately: **one line is one block**, the way a comment box on GitHub
 * reads it, because a note is written in lines rather than in paragraphs that reflow. What is understood is
 * headings, bullet and numbered lists, quotes, fenced code, rules, and inline emphasis, code, links and
 * bare URLs. Anything else is the text it was typed as.
 */
data class Note(val blocks: List<NoteBlock>) {

  /**
   * The class names and addresses written in it, which is the one thing about a note only the heap dump
   * can answer. See [referencesOf].
   */
  val mentions: NoteMentions
    get() {
      val mentions = blocks.flatMap { block -> block.spans }.mapNotNull { it.mention }
      return NoteMentions(
        classNames = mentions.filterIsInstance<NoteMention.ClassName>().map { it.className }.toSet(),
        objectIds = mentions.filterIsInstance<NoteMention.ObjectId>().flatMap { it.objectIds }.toSet()
      )
    }

  /**
   * The same note with everything [references] recognised turned into a link and shortened to read as one.
   *
   * Whatever it didn't recognise is left exactly as it was typed, which is the honest answer: a class name
   * this heap dump has never heard of is a class name someone wrote, not a broken link.
   */
  fun resolvedWith(references: NoteReferences): Note =
    Note(blocks.map { block -> block.mapSpans { it.resolvedWith(references) } })

  companion object {

    val EMPTY = Note(emptyList())

    /** Reads [text] as markdown, with its mentions left for the heap dump to answer. See [resolvedWith]. */
    fun of(text: String): Note = Note(noteBlocksOf(text))
  }
}

/**
 * One line's worth of a note, or one fenced block of code.
 *
 * A block rather than a paragraph because of the one-line-one-block rule above: what a reader typed on its
 * own line is drawn on its own line.
 */
sealed interface NoteBlock {

  /** The styled text of this block, which is empty for a block with no prose in it. */
  val spans: List<NoteSpan>

  /** A line of prose, or an empty one, which is the blank line between two of them. */
  data class Paragraph(override val spans: List<NoteSpan>) : NoteBlock

  /** `#` to `######`, drawn larger the fewer of them there were. */
  data class Heading(
    val level: Int,
    override val spans: List<NoteSpan>
  ) : NoteBlock

  /** A `-`, `*`, `+` or numbered item, indented as far as it was written. */
  data class Item(
    /** What is drawn in front of it: a bullet, or the number as it was typed. */
    val marker: String,
    /** How many levels in it was written, so that a list under a list reads as one. */
    val depth: Int,
    override val spans: List<NoteSpan>
  ) : NoteBlock

  /** A `>` line, which is how something pasted from elsewhere is told from the note about it. */
  data class Quote(override val spans: List<NoteSpan>) : NoteBlock

  /**
   * Everything between two ``` fences, drawn as it was typed.
   *
   * Nothing in it is linked or shortened: code is quoted rather than read, so a class name in it is
   * whatever the code says and an address in it is a number.
   */
  data class Code(val text: String) : NoteBlock {
    override val spans: List<NoteSpan> get() = emptyList()
  }

  /** A `---` line. */
  data object Rule : NoteBlock {
    override val spans: List<NoteSpan> get() = emptyList()
  }

  /** This block with [transform] applied to each of its spans. See [Note.resolvedWith]. */
  fun mapSpans(transform: (NoteSpan) -> NoteSpan): NoteBlock = when (this) {
    is Paragraph -> Paragraph(spans.map(transform))
    is Heading -> Heading(level, spans.map(transform))
    is Item -> Item(marker, depth, spans.map(transform))
    is Quote -> Quote(spans.map(transform))
    is Code, Rule -> this
  }
}

/** A stretch of a block that is drawn one way and leads to one place. */
data class NoteSpan(
  /** What is drawn, which for a resolved [mention] is shorter than what was typed. */
  val text: String,
  val styles: Set<NoteStyle> = emptySet(),
  /** Where clicking it goes, or null for text that leads nowhere. */
  val link: NoteLink? = null,
  /** What the heap dump has to be asked about before this can lead anywhere. See [NoteReferences]. */
  val mention: NoteMention? = null
) {

  /**
   * This span with its mention answered, or unchanged for one the heap dump didn't recognise.
   *
   * Idempotent, because a note is resolved again every time the heap dump is asked: what is drawn is built
   * from the mention rather than from whatever this span is showing now.
   *
   * A span that already leads somewhere keeps leading there. Which is what makes a `shark://` link to an
   * object read as that object without stopping being a link to the window it names.
   */
  internal fun resolvedWith(references: NoteReferences): NoteSpan = when (mention) {
    null -> this
    // Shortened to the simple class name, because that is what every other surface of this window draws:
    // the package is what a note has room for and a rectangle doesn't, and reading it twice is reading it
    // once too often.
    is NoteMention.ClassName -> references.classObjectIds[mention.className]?.let { classObjectId ->
      copy(
        text = mention.className.substringAfterLast('.'),
        link = link ?: NoteLink.Object(classObjectId)
      )
    } ?: this
    // An address on its own says nothing, so a recognised one is drawn as what it points at, with the
    // address it was typed as kept beside it: that is what the reader wrote and what they will search for.
    is NoteMention.ObjectId -> mention.objectIds.firstNotNullOfOrNull { objectId ->
      references.objectNames[objectId]?.let { name ->
        copy(text = "$name (${hexObjectId(objectId)})", link = link ?: NoteLink.Object(objectId))
      }
    } ?: this
  }
}

/** How a span is drawn, beyond the colour a link takes. */
enum class NoteStyle { BOLD, ITALIC, CODE }

/** Where clicking a span of a note goes. */
sealed interface NoteLink {

  /** Out of the app, into whatever the machine calls a browser. */
  data class Web(val url: String) : NoteLink

  /**
   * Into the window the link names, as if the OS had handed it over.
   *
   * Which is the same window most of the time — a note about a heap dump is written beside it — and the
   * point of following it the same way regardless is that a link works identically wherever it is read:
   * in a note, in a chat message, in an issue. See [DeepLink].
   */
  data class Deep(val deepLink: DeepLink) : NoteLink

  /** An object of this heap dump, which is also a class. Opens a tab of its own. */
  data class Object(val objectId: Long) : NoteLink
}

/** Something written in a note that only the heap dump can say whether it exists. */
sealed interface NoteMention {

  /** A dotted name, which is a class of this heap dump or is prose that looks like one. */
  data class ClassName(val className: String) : NoteMention

  /**
   * An address, as every reading of what was typed.
   *
   * Two of them, because a 32 bit heap dump records ids in four bytes and shark widens them by sign, so an
   * object above the 2 GB mark has a negative id whose low 32 bits are what every tool prints. `0xffff8000`
   * is therefore either of two [Long]s, and only the heap dump says which one it has. See [hexObjectId].
   */
  data class ObjectId(val objectIds: List<Long>) : NoteMention
}

/** What a note mentions, gathered so that the heap dump is asked once for all of it. */
data class NoteMentions(
  val classNames: Set<String>,
  val objectIds: Set<Long>
) {

  val isEmpty: Boolean get() = classNames.isEmpty() && objectIds.isEmpty()

  companion object {
    val NONE = NoteMentions(emptySet(), emptySet())
  }
}

/**
 * What a note's mentions turned out to be in the heap dump, which is a read of it. See [referencesOf].
 */
data class NoteReferences(
  /** The class object of each name the heap dump has a class for, and nothing for the names it hasn't. */
  val classObjectIds: Map<String, Long>,
  /** And what each address it has an object at is: `MainActivity instance`. */
  val objectNames: Map<Long, String>
) {

  companion object {
    val NONE = NoteReferences(emptyMap(), emptyMap())
  }
}

/**
 * What [mentions] stand for in this heap dump.
 *
 * Reads the heap dump — a class is found by name over every string in it, though only once per name — so
 * this belongs on the heap dump's thread with every other read, and it is asked once the typing has
 * stopped rather than per keystroke.
 */
fun HeapDominatorTreemap.referencesOf(mentions: NoteMentions): NoteReferences = NoteReferences(
  classObjectIds = mentions.classNames.mapNotNull { className ->
    classObjectIdOrNull(className)?.let { className to it }
  }.toMap(),
  objectNames = mentions.objectIds.mapNotNull { objectId ->
    objectNameOrNull(objectId)?.let { objectId to it }
  }.toMap()
)
