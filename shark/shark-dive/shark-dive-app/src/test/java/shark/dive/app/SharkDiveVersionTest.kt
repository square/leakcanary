package shark.dive.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * That the build script's generated version resource is on the classpath and readable.
 *
 * Worth a test of its own because everything else about the update check keeps working when it isn't: the
 * version silently becomes [SharkDiveVersion.UNKNOWN_VERSION], the check declines to run, and no
 * window ever mentions an update. A wiring mistake would therefore look exactly like "nothing to update
 * to" until a release went out and nobody heard about it.
 */
class SharkDiveVersionTest {

  @get:Rule val logged = RecordedLog()

  @Test fun `this build knows its own version`() {
    assertThat(SharkDiveVersion.current).isNotEqualTo(SharkDiveVersion.UNKNOWN_VERSION)
  }

  /** Which is what jpackage accepts and what [isNewerVersion] can compare — see `gradle.properties`. */
  @Test fun `the version is one jpackage can build and this app can compare`() {
    assertThat(SharkDiveVersion.current).matches("""\d+\.\d+\.\d+""")
    assertThat(isNewerVersion(candidate = SharkDiveVersion.current, current = "0.0.0")).isTrue()
  }
}
