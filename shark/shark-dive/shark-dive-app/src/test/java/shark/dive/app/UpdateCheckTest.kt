package shark.dive.app

import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * What the app concludes from a release manifest. No network: the whole point of [UpdateCheck] taking a
 * `fetchManifest` function is that everything that can go wrong here is reachable without one.
 */
class UpdateCheckTest {

  /** So that a log line built from the wrong state fails here rather than in a session nobody reads. */
  @get:Rule val logged = RecordedLog()

  @Test fun `a later release is an update`() {
    val update = checkAgainst(released = "0.2.0", current = "0.1.0")

    assertThat(update?.version).isEqualTo("0.2.0")
    assertThat(update?.releaseUrl).isEqualTo(RELEASE_URL)
  }

  @Test fun `the released version being the running one is not an update`() {
    assertThat(checkAgainst(released = "0.1.0", current = "0.1.0")).isNull()
  }

  @Test fun `a release older than this run is not an update`() {
    assertThat(checkAgainst(released = "0.1.0", current = "0.2.0")).isNull()
  }

  /**
   * The case that matters for a promotion gate: the rolling manifest points at an older release than the
   * one someone happens to be running, and that must not read as an update.
   */
  @Test fun `a run ahead of the promoted release is left alone`() {
    assertThat(checkAgainst(released = "0.1.0", current = "0.1.3")).isNull()
  }

  @Test fun `a run that does not know its version is never offered an update`() {
    val update = UpdateCheck(
      currentVersion = SharkDiveVersion.UNKNOWN_VERSION,
      fetchManifest = { manifest("99.0.0") }
    ).check()

    assertThat(update).isNull()
    assertThat(logged).anyMatch { it.contains("no version") }
  }

  @Test fun `being offline is not a failure`() {
    val update = UpdateCheck(
      currentVersion = "0.1.0",
      fetchManifest = { throw IOException("No route to host") }
    ).check()

    assertThat(update).isNull()
    assertThat(logged).anyMatch { it.contains("Could not fetch") }
  }

  @Test fun `no manifest yet is not a failure`() {
    val update = UpdateCheck(currentVersion = "0.1.0", fetchManifest = { null }).check()

    assertThat(update).isNull()
  }

  /** What GitHub serves for a tag that doesn't exist: a page, which `Properties` reads without complaint. */
  @Test fun `a page where the manifest should be is not an update`() {
    val update = UpdateCheck(
      currentVersion = "0.1.0",
      fetchManifest = { "<!DOCTYPE html><html><body>Not Found</body></html>" }
    ).check()

    assertThat(update).isNull()
  }

  @Test fun `a manifest missing the release url is not an update`() {
    assertThat(parseReleaseManifest("version=0.2.0")).isNull()
  }

  @Test fun `a manifest missing the version is not an update`() {
    assertThat(parseReleaseManifest("releaseUrl=$RELEASE_URL")).isNull()
  }

  @Test fun `a released version that is not a version is not an update`() {
    val update = UpdateCheck(
      currentVersion = "0.1.0",
      fetchManifest = { manifest("0.2.0-alpha-1") }
    ).check()

    assertThat(update).isNull()
  }

  /**
   * The manifest as `.github/workflows/promote-shark-dive.yml` actually writes it, comments and all.
   *
   * The workflow writes this file and this code reads it, in two languages with nothing between them to
   * keep them agreeing, so the format is worth pinning from this side. Change one and this fails.
   */
  @Test fun `the manifest the promote workflow writes is one this reads`() {
    val written = """
      # Which Shark Dive release is currently being offered to running copies of the app.
      # Written by .github/workflows/promote-shark-dive.yml. Read by the app on startup.
      version=1.2.0
      releaseUrl=https://github.com/square/leakcanary/releases/tag/shark-dive-1.2.0

    """.trimIndent()

    val update = parseReleaseManifest(written)

    assertThat(update).isEqualTo(
      AvailableUpdate(
        version = "1.2.0",
        releaseUrl = "https://github.com/square/leakcanary/releases/tag/shark-dive-1.2.0"
      )
    )
  }

  @Test fun `a shorter version compares by the components it has`() {
    assertThat(isNewerVersion(candidate = "0.2", current = "0.1.9")).isTrue()
    assertThat(isNewerVersion(candidate = "0.2", current = "0.2.0")).isFalse()
    assertThat(isNewerVersion(candidate = "1", current = "0.9.9")).isTrue()
  }

  /** Component by component, not by string order, which is where `0.10.0` and `0.9.0` part company. */
  @Test fun `a two digit component is later than a one digit one`() {
    assertThat(isNewerVersion(candidate = "0.10.0", current = "0.9.0")).isTrue()
    assertThat(isNewerVersion(candidate = "0.9.0", current = "0.10.0")).isFalse()
  }

  private fun checkAgainst(
    released: String,
    current: String
  ): AvailableUpdate? = UpdateCheck(
    currentVersion = current,
    fetchManifest = { manifest(released) }
  ).check()

  private fun manifest(version: String) = "version=$version\nreleaseUrl=$RELEASE_URL\n"

  private companion object {
    const val RELEASE_URL = "https://github.com/square/leakcanary/releases/tag/shark-dive-0.2.0"
  }
}
