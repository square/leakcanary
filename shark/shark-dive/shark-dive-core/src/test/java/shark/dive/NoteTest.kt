package shark.dive

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * What a note means, which is everything about this feature that doesn't need a window or a heap dump: the
 * markdown, the shortening, and which stretches of it lead somewhere.
 *
 * The heap dump's half is [NoteReferencesTest], and it is only ever asked about a [NoteMention] this file
 * says was found.
 */
class NoteTest {

  @Test fun `a note nobody has written is empty`() {
    assertThat(Note.of("")).isEqualTo(Note.EMPTY)
  }

  @Test fun `a line of prose is a paragraph`() {
    assertThat(Note.of("The tab strip holds them all")).isEqualTo(
      Note(listOf(NoteBlock.Paragraph(listOf(NoteSpan("The tab strip holds them all")))))
    )
  }

  /** No blank line between them, and no two spaces at the end of the first: a note is written in lines. */
  @Test fun `two lines are two blocks`() {
    val note = Note.of("What holds it\nThe tab strip")

    assertThat(note.blocks).hasSize(2)
    assertThat(textOf(note)).isEqualTo("What holds it\nThe tab strip")
  }

  @Test fun `a heading is drawn by how many hashes it was written with`() {
    assertThat(Note.of("## What holds it").blocks)
      .containsExactly(NoteBlock.Heading(level = 2, spans = listOf(NoteSpan("What holds it"))))
  }

  @Test fun `a list under a list is indented one level further`() {
    val note = Note.of("- the activity\n  - the window\n* the view")

    assertThat(note.blocks).containsExactly(
      NoteBlock.Item(marker = "•", depth = 0, spans = listOf(NoteSpan("the activity"))),
      NoteBlock.Item(marker = "•", depth = 1, spans = listOf(NoteSpan("the window"))),
      NoteBlock.Item(marker = "•", depth = 0, spans = listOf(NoteSpan("the view")))
    )
  }

  /** A note is often a list of the objects of one leak, numbered from wherever they were copied. */
  @Test fun `a numbered item keeps the number it was written with`() {
    assertThat(Note.of("4. the fourth step").blocks)
      .containsExactly(NoteBlock.Item(marker = "4.", depth = 0, spans = listOf(NoteSpan("the fourth step"))))
  }

  @Test fun `a quote and a rule are what they were written as`() {
    assertThat(Note.of("> pasted from the log\n---").blocks).containsExactly(
      NoteBlock.Quote(listOf(NoteSpan("pasted from the log"))),
      NoteBlock.Rule
    )
  }

  @Test fun `fenced code is quoted rather than read`() {
    val note = Note.of("```\nval leak = com.example.Holder at 0x12ab34cd\n```")

    assertThat(note.blocks)
      .containsExactly(NoteBlock.Code("val leak = com.example.Holder at 0x12ab34cd"))
    // Nothing in it is a name of the heap dump, so nothing in it is linked or shortened.
    assertThat(note.mentions).isEqualTo(NoteMentions.NONE)
  }

  @Test fun `a fence left open is code all the way down`() {
    assertThat(Note.of("```\nstill typing").blocks).containsExactly(NoteBlock.Code("still typing"))
  }

  @Test fun `a web link opens in a browser`() {
    val spans = spansOf("See https://example.com/leaks")

    assertThat(spans).containsExactly(
      NoteSpan("See "),
      NoteSpan(text = "https://example.com/leaks", link = NoteLink.Web("https://example.com/leaks"))
    )
  }

  @Test fun `a link at the end of a sentence is not a link to the sentence`() {
    val spans = spansOf("See https://example.com/leaks.")

    assertThat(spans.map { it.text }).containsExactly("See ", "https://example.com/leaks", ".")
  }

  @Test fun `a github issue is shortened the way github shortens it`() {
    val url = "https://github.com/square/leakcanary/issues/2841"

    assertThat(spansOf(url).single())
      .isEqualTo(NoteSpan(text = "square/leakcanary#2841", link = NoteLink.Web(url)))
  }

  @Test fun `a link to a comment on an issue says it is one`() {
    val url = "https://github.com/square/leakcanary/issues/2841#issuecomment-1234567"

    assertThat(spansOf(url).single().text).isEqualTo("square/leakcanary#2841 (comment)")
  }

  @Test fun `a pull request is shortened like an issue`() {
    assertThat(spansOf("https://github.com/square/leakcanary/pull/2950").single().text)
      .isEqualTo("square/leakcanary#2950")
  }

  @Test fun `a commit is its first seven characters`() {
    assertThat(spansOf("https://github.com/square/leakcanary/commit/ca8c455806f2b1e").single().text)
      .isEqualTo("square/leakcanary@ca8c455")
  }

  @Test fun `a repository is the owner and the repository`() {
    assertThat(spansOf("https://github.com/square/leakcanary").single().text)
      .isEqualTo("square/leakcanary")
  }

  /** Shortening a URL whose shape github doesn't shorten would hide where it goes. */
  @Test fun `anything else on github is left as it was typed`() {
    val url = "https://github.com/square/leakcanary/blob/main/docs/changelog.md"

    assertThat(spansOf(url).single()).isEqualTo(NoteSpan(text = url, link = NoteLink.Web(url)))
  }

  @Test fun `a markdown link is drawn as what it says`() {
    val spans = spansOf("[the leak](https://example.com/leaks)")

    assertThat(spans.single())
      .isEqualTo(NoteSpan(text = "the leak", link = NoteLink.Web("https://example.com/leaks")))
  }

  @Test fun `emphasis is what markdown spells it as`() {
    assertThat(spansOf("**held** and *not held* and `mAttachInfo`")).containsExactly(
      NoteSpan(text = "held", styles = setOf(NoteStyle.BOLD)),
      NoteSpan(" and "),
      NoteSpan(text = "not held", styles = setOf(NoteStyle.ITALIC)),
      NoteSpan(" and "),
      NoteSpan(text = "mAttachInfo", styles = setOf(NoteStyle.CODE))
    )
  }

  /** Emphasis asks for a non-space either side of it, which is what leaves arithmetic and names alone. */
  @Test fun `a multiplication is not italics and a field name is not emphasis`() {
    assertThat(spansOf("2 * 3 * 4").single().text).isEqualTo("2 * 3 * 4")
    assertThat(spansOf("mAttachInfo_2 held it").single().text).isEqualTo("mAttachInfo_2 held it")
  }

  @Test fun `a class name of the heap dump is linked and shortened to its simple name`() {
    val note = Note.of("Held by com.example.Holder").resolvedWith(
      NoteReferences(classObjectIds = mapOf("com.example.Holder" to 0x42L), objectNames = emptyMap())
    )

    assertThat(note.blocks.single().spans).containsExactly(
      NoteSpan("Held by "),
      NoteSpan(
        text = "Holder",
        link = NoteLink.Object(0x42L),
        mention = NoteMention.ClassName("com.example.Holder")
      )
    )
  }

  /** Which is the honest answer: a name this dump hasn't got is a name somebody wrote, not a broken link. */
  @Test fun `a name the heap dump has never heard of stays as it was typed`() {
    val note = Note.of("Held by com.example.Holder").resolvedWith(NoteReferences.NONE)

    assertThat(note.blocks.single().spans.last())
      .isEqualTo(NoteSpan(text = "com.example.Holder", mention = NoteMention.ClassName("com.example.Holder")))
  }

  @Test fun `a class name in backticks is linked and stays monospaced`() {
    val note = Note.of("Held by `com.example.Holder`").resolvedWith(
      NoteReferences(classObjectIds = mapOf("com.example.Holder" to 0x42L), objectNames = emptyMap())
    )

    assertThat(note.blocks.single().spans.last()).isEqualTo(
      NoteSpan(
        text = "Holder",
        styles = setOf(NoteStyle.CODE),
        link = NoteLink.Object(0x42L),
        mention = NoteMention.ClassName("com.example.Holder")
      )
    )
  }

  @Test fun `an address is drawn as what the heap dump has at it`() {
    val note = Note.of("Look at 0x12ab34cd").resolvedWith(
      NoteReferences(classObjectIds = emptyMap(), objectNames = mapOf(0x12ab34cdL to "MainActivity instance"))
    )

    assertThat(note.blocks.single().spans.last()).isEqualTo(
      NoteSpan(
        text = "MainActivity instance (0x12ab34cd)",
        link = NoteLink.Object(0x12ab34cdL),
        mention = NoteMention.ObjectId(listOf(0x12ab34cdL))
      )
    )
  }

  /**
   * An address above the 2 GB mark of a 32 bit dump is a negative id in the tree, and what every tool —
   * including this app's own labels — prints for it is the low 32 bits. So both readings are offered and
   * the dump picks.
   */
  @Test fun `an address of a 32 bit heap dump is the negative id it is stored as`() {
    val negativeId = -2112345088L
    val note = Note.of("Look at 0x82182c00").resolvedWith(
      NoteReferences(classObjectIds = emptyMap(), objectNames = mapOf(negativeId to "Bitmap instance"))
    )

    val span = note.blocks.single().spans.last()
    assertThat(span.text).isEqualTo("Bitmap instance (0x82182c00)")
    assertThat(span.link).isEqualTo(NoteLink.Object(negativeId))
  }

  @Test fun `an address the heap dump has no object at stays as it was typed`() {
    val note = Note.of("Look at 0x12ab34cd").resolvedWith(NoteReferences.NONE)

    assertThat(note.blocks.single().spans.last().text).isEqualTo("0x12ab34cd")
    assertThat(note.blocks.single().spans.last().link).isNull()
  }

  @Test fun `a shark link is followed inside the app and named after the place it leads to`() {
    val spans = spansOf("Everything: shark://abcd2345/leaks")

    assertThat(spans.last()).isEqualTo(
      NoteSpan(text = "Leaks", link = NoteLink.Deep(DeepLink("abcd2345", Place.Leaks())))
    )
  }

  /**
   * The link is left alone by resolving, which is what keeps it a link to the window it names — the same
   * dump is often open twice, and a note is not the place to decide which of them was meant.
   */
  @Test fun `a shark link to an object reads as the object and still leads to that window`() {
    val note = Note.of("shark://abcd2345/object?id=0x12ab34cd").resolvedWith(
      NoteReferences(classObjectIds = emptyMap(), objectNames = mapOf(0x12ab34cdL to "MainActivity instance"))
    )

    val span = note.blocks.single().spans.single()
    assertThat(span.text).isEqualTo("MainActivity instance (0x12ab34cd)")
    assertThat(span.link)
      .isEqualTo(NoteLink.Deep(DeepLink("abcd2345", Place.Object(0x12ab34cdL))))
  }

  @Test fun `a shark link nobody has opened yet says which object it leads to`() {
    assertThat(spansOf("shark://abcd2345/object?id=0x12ab34cd").single().text)
      .isEqualTo("Object 0x12ab34cd")
  }

  @Test fun `a shark link to a place this app has no screen for stays as it was typed`() {
    val spans = spansOf("shark://abcd2345/dominators")

    assertThat(spans.single()).isEqualTo(NoteSpan("shark://abcd2345/dominators"))
  }

  @Test fun `everything the heap dump has to be asked about is gathered once`() {
    val note = Note.of(
      "com.example.Holder holds com.example.Holder at 0x1, and 0x2 holds nothing"
    )

    assertThat(note.mentions).isEqualTo(
      NoteMentions(classNames = setOf("com.example.Holder"), objectIds = setOf(1L, 2L))
    )
  }

  /**
   * A note is resolved again every time the heap dump answers, so resolving one twice has to be the same
   * note: anything built from what a span is showing rather than from its mention would compound.
   */
  @Test fun `resolving a note twice is resolving it once`() {
    val references = NoteReferences(
      classObjectIds = mapOf("com.example.Holder" to 0x42L),
      objectNames = mapOf(1L to "Holder instance")
    )
    val resolved = Note.of("com.example.Holder at 0x1").resolvedWith(references)

    assertThat(resolved.resolvedWith(references)).isEqualTo(resolved)
  }

  /**
   * The other reading, for text that was wrapped to fit a column rather than typed into a box. Verbatim from
   * `AndroidReferenceMatchers.ACCOUNT_MANAGER`, because the shape being read here is a real one: Shark's
   * library leak patterns carry their description as a `"""` block, and the leaks screen draws it.
   */
  @Test fun `wrapped prose is one paragraph rather than a block per line`() {
    val note = Note.ofDocument(WRAPPED_DESCRIPTION)

    assertThat(textOf(note)).isEqualTo(
      "AccountManager.AmsTask.Response is a stub, and as all stubs it's held in memory by a native ref " +
        "until the calling side gets GCed, which can happen long after the stub is no longer of use. " +
        "https://issuetracker.google.com/issues/318303120"
    )
  }

  /** Which is what made rendering these as note markdown worth doing: half of them end in one. */
  @Test fun `a URL in wrapped prose leads to it`() {
    val links = Note.ofDocument(WRAPPED_DESCRIPTION).blocks.single().spans.mapNotNull { it.link }

    assertThat(links).containsExactly(NoteLink.Web("https://issuetracker.google.com/issues/318303120"))
  }

  /** And read as a note it would be four blocks, three of them ending mid-sentence. */
  @Test fun `the same prose read as a note is a block per line`() {
    assertThat(Note.of(WRAPPED_DESCRIPTION).blocks).hasSize(4)
  }

  private fun spansOf(text: String): List<NoteSpan> = Note.of(text).blocks.single().spans

  private fun textOf(note: Note): String =
    note.blocks.joinToString("\n") { block -> block.spans.joinToString("") { it.text } }

  private companion object {
    val WRAPPED_DESCRIPTION = """
      AccountManager.AmsTask.Response is a stub, and as all stubs it's held in memory by a
      native ref until the calling side gets GCed, which can happen long after the stub is no
      longer of use.
      https://issuetracker.google.com/issues/318303120
    """.trimIndent()
  }
}
