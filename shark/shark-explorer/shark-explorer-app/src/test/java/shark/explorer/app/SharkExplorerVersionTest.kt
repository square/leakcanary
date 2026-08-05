package shark.explorer.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * That the build script's generated version resource is on the classpath and readable.
 *
 * Worth a test of its own because everything else about the update check keeps working when it isn't: the
 * version silently becomes [SharkExplorerVersion.UNKNOWN_VERSION], the check declines to run, and no
 * window ever mentions an update. A wiring mistake would therefore look exactly like "nothing to update
 * to" until a release went out and nobody heard about it.
 */
class SharkExplorerVersionTest {

  @get:Rule val logged = RecordedLog()

  @Test fun `this build knows its own version`() {
    assertThat(SharkExplorerVersion.current).isNotEqualTo(SharkExplorerVersion.UNKNOWN_VERSION)
  }

  /** Which is what jpackage accepts and what [isNewerVersion] can compare — see `gradle.properties`. */
  @Test fun `the version is one jpackage can build and this app can compare`() {
    assertThat(SharkExplorerVersion.current).matches("""\d+\.\d+\.\d+""")
    assertThat(isNewerVersion(candidate = SharkExplorerVersion.current, current = "0.0.0")).isTrue()
  }
}
