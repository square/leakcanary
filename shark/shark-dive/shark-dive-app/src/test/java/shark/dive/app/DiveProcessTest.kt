package shark.dive.app

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * How this run would start another one, which is what an agent that found no window needs.
 *
 * Only the parts that can be checked from a test JVM. Whether the command actually opens a window is a
 * package away — see the deep link section of `shark/shark-dive/AGENTS.md` — but the three ways it goes
 * wrong silently are a renamed `Main.kt`, a classpath that never reaches the command, and a path with a `.`
 * left in it, and all three are here.
 */
class DiveProcessTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `the main class is the one Kotlin generates`() {
    // A rename of `Main.kt` fails this rather than a feature nobody tries until an agent needs a window.
    assertThat(Class.forName(MAIN_CLASS)).isNotNull
  }

  @Test
  fun `a JVM starts another run of itself with this classpath`() {
    val command = relaunchCommand()

    // The test runner is a JVM with a classpath, which is also what `./gradlew run` is.
    assertThat(command).isNotNull
    assertThat(command!!.last()).isEqualTo(MAIN_CLASS)
    assertThat(command).contains("-cp")
    assertThat(command).contains(System.getProperty("java.class.path"))
  }

  @Test
  fun `a JVM is not what the OS should open links with`() {
    // The other half of [relaunchCommand], and deliberately the opposite answer: registering `java` would
    // tell the OS to open `shark://` links with a JVM and no classpath.
    assertThat(launcherPathOrNull()).isNull()
  }

  /**
   * A path handed to the OS has no `.` or `..` left in it, which is the one of these three with no symptom.
   *
   * macOS `open -n -a` reads a path with a `.` segment in it as an application *name* and launches whichever
   * Shark Dive it can find instead — so a run started from a repository root through `shark-dive.sh` was the
   * installed build, which refused this build's options and exited before publishing itself. What a `--cli`
   * command printed was that it had waited a minute for a run to appear. See [pathToHandTheOs].
   */
  @Test
  fun `a path handed to the OS keeps no dot in it`() {
    val directory = temporaryFolder.newFolder("app")
    val dotted = "${directory.parent}/./${directory.name}"

    // Absolute and there, which is every check short of this one.
    assertThat(File(dotted)).exists()
    assertThat(File(dotted).isAbsolute).isTrue
    assertThat(pathToHandTheOs(dotted)).doesNotContain("/./").isEqualTo(directory.canonicalPath)
  }

  @Test
  fun `a path the file system cannot resolve is handed over anyway`() {
    // Better than dropping it: the caller's alternative to a path is not starting a run at all.
    val missing = "${temporaryFolder.root.canonicalPath}/./nothing-is-here/Shark Dive.app"

    assertThat(pathToHandTheOs(missing)).doesNotContain("/./")
  }
}
