package shark.dive.app

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import shark.dive.DeepLink
import shark.dive.Place

/** What a command line means, without launching anything: see [DiveArguments]. */
class DiveArgumentsTest {

  @Test fun `a run with no arguments opens nothing and calls its window nothing`() {
    val arguments = DiveArguments.parse(emptyList())

    assertThat(arguments.heapDumpFiles).isEmpty()
    assertThat(arguments.titlePrefix).isNull()
  }

  @Test fun `every path is a heap dump to open`() {
    val arguments = DiveArguments.parse(listOf(FIRST_PATH, SECOND_PATH))

    assertThat(arguments.heapDumpFiles).containsExactly(File(FIRST_PATH), File(SECOND_PATH))
  }

  @Test fun `a title can be given with an equals sign or as the next argument`() {
    // Two spellings because a title has spaces in it, and a shell, Gradle's `--args` and a run
    // configuration don't all pass those through the same way.
    val joined = DiveArguments.parse(listOf("--title=$TITLE", FIRST_PATH))
    val separate = DiveArguments.parse(listOf("--title", TITLE, FIRST_PATH))

    assertThat(joined).isEqualTo(separate)
    assertThat(joined.titlePrefix).isEqualTo(TITLE)
    assertThat(joined.heapDumpFiles).containsExactly(File(FIRST_PATH))
  }

  @Test fun `a title given after the heap dump still names the windows`() {
    val arguments = DiveArguments.parse(listOf(FIRST_PATH, "--title=$TITLE"))

    assertThat(arguments.titlePrefix).isEqualTo(TITLE)
  }

  @Test fun `a title with nothing after it says what to type instead`() {
    // Both ways of leaving it out, since a shell that swallows an empty argument produces the first and a
    // hand written command line the second.
    assertThatThrownBy { DiveArguments.parse(listOf("--title")) }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("--title=")
    assertThatThrownBy { DiveArguments.parse(listOf("--title=")) }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("--title=")
  }

  @Test fun `an option nobody knows is not a heap dump`() {
    // A typo taken for a path opens a window saying a heap dump called `--titel` could not be read, which
    // is the wrong thing to go looking for.
    assertThatThrownBy { DiveArguments.parse(listOf("--titel=$TITLE")) }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("--titel=$TITLE")
  }

  @Test fun `a link on the command line is a place to go and not a heap dump to open`() {
    val arguments = DiveArguments.parse(listOf(LINK))

    // Which is how Windows and Linux deliver one: the OS starts a process with the link on its command
    // line, and a link taken for a path would be a window saying that file could not be read.
    assertThat(arguments.deepLinks).containsExactly(DeepLink("abcd2345", Place.Starred))
    assertThat(arguments.heapDumpFiles).isEmpty()
  }

  @Test fun `heap dumps and links can be asked for together`() {
    val arguments = DiveArguments.parse(listOf("--title=$TITLE", FIRST_PATH, LINK))

    assertThat(arguments.heapDumpFiles).containsExactly(File(FIRST_PATH))
    assertThat(arguments.deepLinks).containsExactly(DeepLink("abcd2345", Place.Starred))
    assertThat(arguments.titlePrefix).isEqualTo(TITLE)
  }

  @Test fun `a link nobody can read says what is wrong with it`() {
    // Rather than being taken for a path, which would report a heap dump that could not be found and send
    // whoever typed it looking for a file.
    assertThatThrownBy { DiveArguments.parse(listOf("shark://abcd2345/dominators")) }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("is no place")
  }

  companion object {
    private const val FIRST_PATH = "first.hprof"
    private const val SECOND_PATH = "dumps/second.hprof"
    private const val TITLE = "Hover previews"
    private const val LINK = "shark://abcd2345/starred"
  }
}
