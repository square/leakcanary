package shark.dive

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * What holds the reference to being a reference: a page per topic, each opening with the one sentence its `?`
 * shows, and every page of it published.
 *
 * These are copy rules rather than behaviour, and they are here because copy is what goes wrong quietly. A
 * hint that has grown into a paragraph still draws; a page nobody included still opens in the app. Both are
 * only ever noticed by whoever reads the screen next, which is why they fail the build instead.
 */
class ReferenceTest {

  @Test
  fun `every topic has a page`() {
    // Reading them at all is the assertion: a topic with no file throws saying which, from ReferencePage.
    assertThat(ReferencePage.all.map { it.topic }).isEqualTo(Topic.values().toList())
    ReferencePage.all.forEach { page ->
      assertThat(page.title).describedAs(page.topic.name).isNotBlank()
      assertThat(page.blocks).describedAs(page.topic.name).isNotEmpty()
    }
  }

  /**
   * Because it is a tooltip: it is read while deciding whether to look further, over the top of the thing it
   * is about. The page under it is where the rest goes, and it has no length limit at all.
   */
  @Test
  fun `every hint is one short sentence`() {
    ReferencePage.all.forEach { page ->
      val words = page.hint.trim().split(WHITESPACE)
      assertThat(words.size)
        .describedAs("${page.topic.name} hint, \"${page.hint}\"")
        .isLessThanOrEqualTo(MOST_HINT_WORDS)
      assertThat(page.hint.trim())
        .describedAs("${page.topic.name} hint")
        .endsWith(".")
      assertThat(page.hint.trim().dropLast(1))
        .describedAs("${page.topic.name} hint, which is one sentence")
        .doesNotContain(".")
    }
  }

  /**
   * The half of "one copy of every sentence" that only the repository can answer: the app reads these files
   * off its classpath, and this is what says the website reads the same ones.
   *
   * A page the site doesn't include is a page that is in the app and nowhere else, which is the drift shipping
   * the reference inside the build was meant to rule out. See `copyReference`, and `mkdocs.yml`.
   */
  @Test
  fun `the website publishes every page`() {
    val published = File(System.getProperty(REFERENCE_PAGE_PROPERTY)).readText()

    Topic.values().forEach { topic ->
      assertThat(published)
        .describedAs("docs/shark-dive-reference.md includes ${topic.page}")
        .contains("\"docs/shark-dive-reference/${topic.page}.md\"")
    }
  }

  /** As many as fit a tooltip a reader glances at, which is one line of prose and not three. */
  private companion object {
    const val MOST_HINT_WORDS = 25
    const val REFERENCE_PAGE_PROPERTY = "shark.dive.referencePage"
    val WHITESPACE = Regex("""\s+""")
  }
}
