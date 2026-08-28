package shark.dive

/**
 * Reading the markdown of a note. The other half of [Note], kept apart because a parser reads as one thing
 * and a model as another.
 *
 * What is understood is deliberately a subset — see [Note] for which — and **one line is one block**: no
 * paragraph reflows two lines someone wrote as two, no line has to be ended with two spaces to stay a line,
 * and a list needs no blank line above it. Which is the way a comment box on GitHub reads markdown, and the
 * way anyone typing a note about a heap dump expects it to be read.
 *
 * Nothing here reads the heap dump. What only the heap dump can answer is left as a [NoteMention] for
 * [Note.resolvedWith], so that a note can be re-read on every keystroke and the dump asked once the typing
 * has stopped.
 *
 * No line is logged from here for the same reason: this runs per keystroke, so a line about a link that
 * doesn't parse would be a line per keystroke. A link this can't read stays the text it was typed as, which
 * is what the window shows for it.
 */
internal fun noteBlocksOf(text: String): List<NoteBlock> {
  if (text.isEmpty()) {
    return emptyList()
  }
  val lines = text.split('\n').map { it.removeSuffix("\r") }
  val blocks = mutableListOf<NoteBlock>()
  var index = 0
  while (index < lines.size) {
    if (lines[index].trimStart().startsWith(CODE_FENCE)) {
      val fenced = mutableListOf<String>()
      index++
      while (index < lines.size && !lines[index].trimStart().startsWith(CODE_FENCE)) {
        fenced += lines[index]
        index++
      }
      // A fence with nothing closing it is a block halfway through being typed rather than a mistake, so
      // what is under it is code either way. Stepping past the closing fence, or past the end.
      index++
      blocks += NoteBlock.Code(fenced.joinToString("\n"))
    } else {
      blocks += blockOf(lines[index])
      index++
    }
  }
  return blocks
}

/**
 * Reading the markdown of a written document rather than of a note: a page of the reference, wrapped at the
 * column the rest of this repository is wrapped at.
 *
 * The one difference from [noteBlocksOf] is what a line break means. In a note it means a line break, which
 * is what anyone typing into a box expects and why nothing there has to be ended with two spaces. In a file
 * somebody wrapped to fit a diff it means nothing at all, so a run of prose lines is one paragraph and the
 * blank line between two runs is what separates them — plain markdown, and the same reading the website
 * gives these files.
 *
 * Everything else is [noteBlocksOf]'s, headings and links and fences and all, so a page reads in the window
 * the way a note does and there is one parser to be wrong.
 */
internal fun documentBlocksOf(text: String): List<NoteBlock> = noteBlocksOf(unwrapped(text))

/**
 * The same markdown with each paragraph on one line, which is the shape [noteBlocksOf] reads.
 *
 * Blank lines go with the wrapping they separated: they were the paragraph break, and once the paragraphs
 * are one line each they would be empty paragraphs drawn as blank space on top of the spacing the window
 * already puts between blocks. Inside a fence every line is left exactly as it is, blank ones included —
 * code is the one place a line break is the content.
 */
private fun unwrapped(text: String): String {
  val unwrapped = mutableListOf<String>()
  var isCode = false
  var isParagraph = false
  text.split('\n').map { it.removeSuffix("\r") }.forEach { line ->
    when {
      line.trimStart().startsWith(CODE_FENCE) -> {
        isCode = !isCode
        isParagraph = false
        unwrapped += line
      }
      isCode -> unwrapped += line
      line.isBlank() -> isParagraph = false
      // A heading, a bullet, a quote or a rule is a block of its own, so it neither continues the
      // paragraph above it nor is continued by the line below.
      !isProse(line) -> {
        isParagraph = false
        unwrapped += line
      }
      isParagraph -> unwrapped[unwrapped.lastIndex] = "${unwrapped.last()} ${line.trim()}"
      else -> {
        isParagraph = true
        unwrapped += line.trim()
      }
    }
  }
  return unwrapped.joinToString("\n")
}

/** Whether this line is prose, rather than one of the shapes [blockOf] reads as a block in its own right. */
private fun isProse(line: String): Boolean =
  !RULE.matches(line) && !HEADING.matches(line) && !QUOTE.matches(line) && !ITEM.matches(line)

private fun blockOf(line: String): NoteBlock {
  // Before the list, so that `---` is a rule rather than a bullet with nothing after it.
  if (RULE.matches(line)) {
    return NoteBlock.Rule
  }
  HEADING.matchEntire(line)?.let { heading ->
    return NoteBlock.Heading(
      level = heading.groupValues[1].length,
      spans = inlineSpansOf(heading.groupValues[2])
    )
  }
  QUOTE.matchEntire(line)?.let { quote ->
    return NoteBlock.Quote(inlineSpansOf(quote.groupValues[1]))
  }
  ITEM.matchEntire(line)?.let { item ->
    val number = item.groupValues[3]
    return NoteBlock.Item(
      // A number is drawn as it was typed rather than counted here, so that a list starting at 4 keeps
      // saying 4 — a note is often a list of the objects of one leak, numbered from wherever they were.
      marker = if (number.isEmpty()) BULLET else "$number.",
      depth = item.groupValues[1].replace("\t", TAB_AS_SPACES).length / INDENT_WIDTH,
      spans = inlineSpansOf(item.groupValues[4])
    )
  }
  return NoteBlock.Paragraph(inlineSpansOf(line))
}

/**
 * The styled and linked stretches of one line.
 *
 * Two passes rather than one regex: this one is what markdown spells — code, links, emphasis — and the text
 * between and inside those goes through [mentionSpansOf], which is what this heap dump spells. So a class
 * name is found in a bullet, in bold, and in `backticks`, and is not looked for inside a URL.
 */
private fun inlineSpansOf(
  text: String,
  styles: Set<NoteStyle> = emptySet()
): List<NoteSpan> {
  val spans = mutableListOf<NoteSpan>()
  var index = 0
  INLINE_TOKEN.findAll(text).forEach { token ->
    if (token.range.first > index) {
      spans += mentionSpansOf(text.substring(index, token.range.first), styles)
    }
    spans += tokenSpans(token, styles)
    index = token.range.last + 1
  }
  if (index < text.length) {
    spans += mentionSpansOf(text.substring(index), styles)
  }
  return spans
}

private fun tokenSpans(
  token: MatchResult,
  styles: Set<NoteStyle>
): List<NoteSpan> {
  val groups = token.groups
  groups[CODE_GROUP]?.let { code ->
    // Monospaced, and still read for what it mentions: `com.example.Thing` in backticks is how a class
    // name gets written by anyone who writes markdown, so refusing to link it would be refusing the
    // spelling most notes use.
    return mentionSpansOf(code.value.trim('`'), styles + NoteStyle.CODE)
  }
  groups[LINK_GROUP]?.let { return listOf(markdownLinkSpan(it.value, styles)) }
  groups[URL_GROUP]?.let { return listOf(urlSpan(it.value, styles)) }
  groups[BOLD_GROUP]?.let { bold ->
    return inlineSpansOf(bold.value.drop(BOLD_MARKER_LENGTH).dropLast(BOLD_MARKER_LENGTH), styles + NoteStyle.BOLD)
  }
  groups[ITALIC_GROUP]?.let { italic ->
    return inlineSpansOf(italic.value.drop(1).dropLast(1), styles + NoteStyle.ITALIC)
  }
  return listOf(NoteSpan(text = token.value, styles = styles))
}

/** `[what it says](where it goes)`, which is the way to write a link whose text is prose. */
private fun markdownLinkSpan(
  token: String,
  styles: Set<NoteStyle>
): NoteSpan {
  val target = token.substringAfterLast("](").removeSuffix(")")
  val text = token.removePrefix("[").substringBeforeLast("](")
  val link = when {
    DeepLink.looksLikeOne(target) -> deepLinkOrNull(target)?.let { NoteLink.Deep(it) }
    WEB_SCHEMES.any { target.startsWith(it) } -> NoteLink.Web(target)
    // Anything else — a relative path, a file, a scheme this app has no business opening — is text.
    else -> null
  }
  return NoteSpan(text = text.ifEmpty { target }, styles = styles, link = link)
}

/** A URL written out on its own, which is what pasting one does. */
private fun urlSpan(
  url: String,
  styles: Set<NoteStyle>
): NoteSpan {
  if (DeepLink.looksLikeOne(url)) {
    val deepLink = deepLinkOrNull(url)
    // A `shark://` link that isn't one — a place this app has no screen for, a window missing from it — is
    // left as typed rather than drawn as a link that goes nowhere.
    return if (deepLink == null) NoteSpan(text = url, styles = styles) else deepLinkSpan(deepLink, styles)
  }
  return NoteSpan(text = shortWebText(url), styles = styles, link = NoteLink.Web(url))
}

/**
 * A link to a place in a window, drawn as the place rather than as the URL.
 *
 * The link is kept exactly as it was typed, so it still leads to the window it names — which is usually
 * this one and after a restart is none, and either way is not for a note to decide. See [NoteLink.Deep].
 */
private fun deepLinkSpan(
  deepLink: DeepLink,
  styles: Set<NoteStyle>
): NoteSpan {
  val place = deepLink.place
  val link = NoteLink.Deep(deepLink)
  if (place !is Place.Object) {
    // Every other place says what it is. One that stops doing so shows the URL, which is at least what
    // somebody pasted.
    return NoteSpan(text = place.title ?: deepLink.toUri(), styles = styles, link = link)
  }
  if (place.objectId == HeapDominatorTreemap.ROOT_OBJECT_ID) {
    return NoteSpan(text = HeapDominatorTreemap.ROOT_LABEL, styles = styles, link = link)
  }
  // Named by asking the heap dump, the same way a typed address is: a link to an object of the dump these
  // notes are about reads as that object. See [NoteSpan.resolvedWith], which leaves the link alone.
  return NoteSpan(
    text = "$OBJECT_PREFIX${hexObjectId(place.objectId)}",
    styles = styles,
    link = link,
    mention = NoteMention.ObjectId(listOf(place.objectId))
  )
}

private fun deepLinkOrNull(url: String): DeepLink? = try {
  DeepLink.parse(url)
} catch (notALink: IllegalArgumentException) {
  null
}

/**
 * What a web URL is drawn as, which for GitHub is what GitHub itself would draw.
 *
 * `https://github.com/square/leakcanary/issues/2841` written out in full is nine tenths punctuation, and a
 * note about a heap dump is mostly links to the issue it is about. GitHub shortens those in every comment
 * box it has, so a note that didn't would be the one place they are unreadable. Everything else is drawn as
 * it was typed: shortening an arbitrary URL hides where it goes.
 */
private fun shortWebText(url: String): String {
  val path = url.substringAfter("://").removePrefix("www.")
  if (!path.startsWith(GITHUB_HOST)) {
    return url
  }
  val fragment = path.substringAfter('#', "")
  val segments = path.removePrefix(GITHUB_HOST).substringBefore('?').substringBefore('#')
    .split('/').filter { it.isNotEmpty() }
  val repository = segments.take(2).joinToString("/")
  return when {
    segments.size == 2 -> repository
    segments.size != 4 -> url
    segments[2] == COMMIT_SEGMENT -> "$repository@${segments[3].take(SHORT_SHA_LENGTH)}"
    segments[2] in NUMBERED_SEGMENTS && segments[3].toIntOrNull() != null ->
      "$repository#${segments[3]}${if (isCommentFragment(fragment)) COMMENT_SUFFIX else ""}"
    else -> url
  }
}

/** Which part of an issue or a pull request a fragment points at: a comment, or a review of a diff. */
private fun isCommentFragment(fragment: String): Boolean =
  COMMENT_FRAGMENT_PREFIXES.any { fragment.startsWith(it) }

/**
 * The stretches of a line that only the heap dump can say anything about: a dotted name, and an address.
 *
 * Left as a [NoteMention] with the text as typed, so that a note reads exactly as it was written until the
 * dump has been asked, and goes on doing so for whatever the dump doesn't have.
 */
private fun mentionSpansOf(
  text: String,
  styles: Set<NoteStyle>
): List<NoteSpan> {
  val spans = mutableListOf<NoteSpan>()
  var index = 0
  MENTION_TOKEN.findAll(text).forEach { token ->
    if (token.range.first > index) {
      spans += NoteSpan(text = text.substring(index, token.range.first), styles = styles)
    }
    spans += mentionSpan(token, styles)
    index = token.range.last + 1
  }
  if (index < text.length) {
    spans += NoteSpan(text = text.substring(index), styles = styles)
  }
  return spans
}

private fun mentionSpan(
  token: MatchResult,
  styles: Set<NoteStyle>
): NoteSpan {
  val address = token.groups[OBJECT_ID_GROUP]
    ?: return NoteSpan(
      text = token.value,
      styles = styles,
      mention = NoteMention.ClassName(token.value)
    )
  val objectIds = objectIdsOf(address.value)
  return NoteSpan(
    text = address.value,
    styles = styles,
    mention = if (objectIds.isEmpty()) null else NoteMention.ObjectId(objectIds)
  )
}

/**
 * Every [Long] `0x…` can mean, which is one of them or two.
 *
 * Two when it fits in 32 bits with the top one set: shark reads a 32 bit heap dump's four byte ids as
 * [Long]s widened by sign, so the object every other tool calls `0xffff8000` is the negative id
 * `0xffffffffffff8000` in the tree, and both readings have to be offered. See [NoteMention.ObjectId].
 */
private fun objectIdsOf(text: String): List<Long> {
  val address = try {
    java.lang.Long.parseUnsignedLong(text.substring(HEX_PREFIX.length), HEX_RADIX)
  } catch (notAnAddress: NumberFormatException) {
    return emptyList()
  }
  return if (address and INT_MASK == address && address and INT_SIGN_BIT != 0L) {
    listOf(address, address.toInt().toLong())
  } else {
    listOf(address)
  }
}

private const val CODE_FENCE = "```"

/** What a bullet is drawn as, whichever of `-`, `*` and `+` was typed. */
private const val BULLET = "•"

private const val INDENT_WIDTH = 2
private const val TAB_AS_SPACES = "  "
private const val BOLD_MARKER_LENGTH = 2

private const val OBJECT_PREFIX = "Object "

private const val GITHUB_HOST = "github.com/"
private const val COMMIT_SEGMENT = "commit"
private val NUMBERED_SEGMENTS = setOf("issues", "pull", "discussions")
private const val COMMENT_SUFFIX = " (comment)"

/** As GitHub writes them: a comment on an issue, a review of a pull request, a reply under either. */
private val COMMENT_FRAGMENT_PREFIXES =
  listOf("issuecomment", "discussion_r", "pullrequestreview", "discussion-")

private const val SHORT_SHA_LENGTH = 7

private val WEB_SCHEMES = listOf("https://", "http://")

private const val HEX_PREFIX = "0x"
private const val HEX_RADIX = 16
private const val INT_MASK = 0xffffffffL
private const val INT_SIGN_BIT = 0x80000000L

private val RULE = Regex(""" {0,3}(?:-{3,}|\*{3,}|_{3,}) *""")
private val HEADING = Regex(""" {0,3}(#{1,6}) +(.*)""")
private val QUOTE = Regex(""" {0,3}> ?(.*)""")
private val ITEM = Regex("""([ \t]*)(?:([-*+])|(\d+)[.)]) +(.*)""")

private const val CODE_GROUP = "code"
private const val LINK_GROUP = "link"
private const val URL_GROUP = "url"
private const val BOLD_GROUP = "bold"
private const val ITALIC_GROUP = "italic"

/**
 * What markdown spells inline, in the order it is looked for: whichever starts earliest in the line wins,
 * and at one place in the line the first of these does.
 *
 * Emphasis asks for a non-space either side of the text, which is what keeps `2 * 3 * 4` from being a
 * multiplication written in italics, and the underscore forms ask for a non-word character outside them,
 * which is what keeps `mAttachInfo_2` in one piece.
 */
private val INLINE_TOKEN = Regex(
  listOf(
    """(?<$CODE_GROUP>`[^`\n]+`)""",
    """(?<$LINK_GROUP>\[[^\]\n]*\]\([^)\s]+\))""",
    // Trailing punctuation is left out, so that a link at the end of a sentence isn't a link to the
    // sentence: `[^…]*` gives back whatever it has to for the last character to be part of the URL.
    """(?<$URL_GROUP>(?:https?|${DeepLink.SCHEME})://[^\s<>()\[\]"'`]*[^\s<>()\[\]"'`.,;:!?])""",
    """(?<$BOLD_GROUP>\*\*\S(?:[^*]*\S)?\*\*|(?<!\w)__\S(?:[^_]*\S)?__(?!\w))""",
    """(?<$ITALIC_GROUP>\*\S(?:[^*]*\S)?\*|(?<!\w)_\S(?:[^_]*\S)?_(?!\w))"""
  ).joinToString("|")
)

private const val OBJECT_ID_GROUP = "objectId"

/** A dotted name as the JVM spells one, `${'$'}` and all: an inner class of a heap dump is `Outer${'$'}Inner`. */
private const val IDENTIFIER = """[A-Za-z_${'$'}][\w${'$'}]*"""

/**
 * What this heap dump spells: an address, and a name with a package in front of it.
 *
 * Neither may start part way into a longer name, which is what the lookbehinds are for — the tail of
 * `com.example.Thing` is not a class called `example.Thing`, and the tail of `0x1f` is not `x1f`.
 */
private val MENTION_TOKEN = Regex(
  """(?<$OBJECT_ID_GROUP>(?<![\w.])0[xX][0-9a-fA-F]{1,16}\b)""" +
    """|(?<![\w.${'$'}])$IDENTIFIER(?:\.$IDENTIFIER)+"""
)
