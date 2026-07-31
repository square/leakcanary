package shark.explorer.app

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

/** What a command line means, without launching anything: see [ExplorerArguments]. */
class ExplorerArgumentsTest {

  @Test fun `a run with no arguments opens nothing and calls its window nothing`() {
    val arguments = ExplorerArguments.parse(emptyList())

    assertThat(arguments.heapDumpFiles).isEmpty()
    assertThat(arguments.titlePrefix).isNull()
  }

  @Test fun `every path is a heap dump to open`() {
    val arguments = ExplorerArguments.parse(listOf(FIRST_PATH, SECOND_PATH))

    assertThat(arguments.heapDumpFiles).containsExactly(File(FIRST_PATH), File(SECOND_PATH))
  }

  @Test fun `a title can be given with an equals sign or as the next argument`() {
    // Two spellings because a title has spaces in it, and a shell, Gradle's `--args` and a run
    // configuration don't all pass those through the same way.
    val joined = ExplorerArguments.parse(listOf("--title=$TITLE", FIRST_PATH))
    val separate = ExplorerArguments.parse(listOf("--title", TITLE, FIRST_PATH))

    assertThat(joined).isEqualTo(separate)
    assertThat(joined.titlePrefix).isEqualTo(TITLE)
    assertThat(joined.heapDumpFiles).containsExactly(File(FIRST_PATH))
  }

  @Test fun `a title given after the heap dump still names the windows`() {
    val arguments = ExplorerArguments.parse(listOf(FIRST_PATH, "--title=$TITLE"))

    assertThat(arguments.titlePrefix).isEqualTo(TITLE)
  }

  @Test fun `a title with nothing after it says what to type instead`() {
    // Both ways of leaving it out, since a shell that swallows an empty argument produces the first and a
    // hand written command line the second.
    assertThatThrownBy { ExplorerArguments.parse(listOf("--title")) }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("--title=")
    assertThatThrownBy { ExplorerArguments.parse(listOf("--title=")) }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("--title=")
  }

  @Test fun `an option nobody knows is not a heap dump`() {
    // A typo taken for a path opens a window saying a heap dump called `--titel` could not be read, which
    // is the wrong thing to go looking for.
    assertThatThrownBy { ExplorerArguments.parse(listOf("--titel=$TITLE")) }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("--titel=$TITLE")
  }

  companion object {
    private const val FIRST_PATH = "first.hprof"
    private const val SECOND_PATH = "dumps/second.hprof"
    private const val TITLE = "Hover previews"
  }
}
