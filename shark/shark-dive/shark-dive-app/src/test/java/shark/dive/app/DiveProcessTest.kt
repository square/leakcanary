package shark.dive.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * How this run would start another one, which is what an agent that found no window needs.
 *
 * Only the parts that can be checked from a test JVM. Whether the command actually opens a window is a
 * package away — see the deep link section of `shark/shark-dive/AGENTS.md` — but the two ways it goes
 * wrong silently are a renamed `Main.kt` and a classpath that never reaches the command, and both are here.
 */
class DiveProcessTest {

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
}
