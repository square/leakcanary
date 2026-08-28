package shark.dive

/**
 * Something the UI names that takes more than a label to know: the pages of the reference, and the one
 * sentence each of them opens with.
 *
 * **The sentence on screen is the page's own first sentence.** Both are read out of one markdown file, so
 * the hint under a `?` cannot say something the page it leads to has stopped saying — which is what a hint
 * written in Kotlin beside a page written in markdown becomes after one of the two is edited.
 *
 * The files are `docs/shark-dive-reference/`, copied onto the classpath by the `copyReference` task in this
 * module's build script and published as `docs/shark-dive-reference.md`, which includes the same files. So a
 * build carries the reference it was built with: an older release shows what was true of it, rather than
 * following a link to a page written about a newer one.
 *
 * See [Place.Reference], which is where a `?` leads, and `Explain`, which draws it.
 */
enum class Topic(
  /** Its file under `docs/shark-dive-reference/`, and how [DeepLink] spells it. */
  val page: String
) {
  LEAK_NAME("leak-name"),
  FAULTY_REFERENCE("faulty-reference"),
  LEAK_FINGERPRINT("leak-fingerprint"),
  LIBRARY_LEAKS("library-leaks"),
  REACHABILITY_STRENGTH("reachability-strength"),
  STUCK_SHADING("stuck-shading"),
  WEAKER_REFERENCES("weaker-references"),
  OTHER_WAYS("other-ways");

  companion object {
    /** Which topic a link names, or null for a page this build has never heard of. */
    fun ofPage(page: String): Topic? = values().firstOrNull { it.page == page }
  }
}

/**
 * One page of the reference, as the window draws it.
 *
 * Named for the page rather than for the reference: `Reference` in this package would sit one letter away
 * from `shark.Reference`, a reference of the heap graph, which a dozen files here import.
 */
class ReferencePage internal constructor(
  val topic: Topic,
  /** Its heading, which is also what a tab showing it is called. */
  val title: String,
  /**
   * Its opening sentence, which is what the `?` says on hover.
   *
   * One sentence, because a tooltip is read while deciding whether to look further and a paragraph there is
   * a paragraph in the way of the thing it is about. `ReferenceTest` is what holds it to that.
   */
  val hint: String,
  /** The page itself, opening with the sentence [hint] is. */
  val blocks: List<NoteBlock>
) {

  /** The pages, read once off the classpath. */
  companion object {

    /** In [Topic] order, which is the order `docs/shark-dive-reference.md` includes them in. */
    val all: List<ReferencePage> by lazy { Topic.values().map { pageOf(it) } }

    fun of(topic: Topic): ReferencePage = all[topic.ordinal]

    private fun pageOf(topic: Topic): ReferencePage {
      val path = "/$RESOURCE_DIRECTORY/${topic.page}$MARKDOWN_SUFFIX"
      val blocks = Note.ofDocument(markdownOf(topic, path)).blocks
      val heading = blocks.firstOrNull()
      check(heading is NoteBlock.Heading) {
        "\"$path\" has to start with its title as a \"## \" heading, which is what names the page here " +
          "and what docs/shark-dive-reference.md draws under its own title, and it starts with $heading."
      }
      val body = blocks.drop(1)
      val opening = body.firstOrNull()
      check(opening is NoteBlock.Paragraph) {
        "The paragraph under the title of \"$path\" is what the `?` leading to it says, and there is " +
          "$opening under it instead."
      }
      return ReferencePage(
        topic = topic,
        title = heading.spans.plainText(),
        hint = opening.spans.plainText(),
        blocks = body
      )
    }

    private fun markdownOf(topic: Topic, path: String): String =
      checkNotNull(
        ReferencePage::class.java.getResourceAsStream(path)?.use {
          it.readBytes().toString(Charsets.UTF_8)
        }
      ) {
        "There is no \"$path\" on the classpath, so ${topic.name} has no page to lead to. These are copied " +
          "out of docs/shark-dive-reference by the copyReference task in shark-dive-core, so a topic added " +
          "to Topic without a file of that name beside the others is what this is."
      }

    /** What the markdown says with the styling taken off, which is all a tooltip and a tab title need. */
    private fun List<NoteSpan>.plainText(): String = joinToString("") { it.text }

    /** Where `copyReference` puts them, under the resource root. */
    private const val RESOURCE_DIRECTORY = "shark-dive-reference"

    private const val MARKDOWN_SUFFIX = ".md"
  }
}
