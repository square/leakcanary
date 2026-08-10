package shark.explorer.app

import java.util.Properties
import shark.SharkLog

/**
 * Which version of the explorer this is, which is what [UpdateCheck] compares a release against.
 *
 * Read off the classpath from a file the build script generates out of `SHARK_EXPLORER_VERSION` — see
 * `writeVersionResource` — rather than from the jar manifest, so that `./gradlew run` and the tests
 * report the same version a packaged build does.
 */
internal object SharkExplorerVersion {

  /** [UNKNOWN_VERSION] when the resource is missing, which is a classpath built by hand. */
  val current: String by lazy { readVersion() }

  private fun readVersion(): String {
    val stream = javaClass.getResourceAsStream("/$VERSION_RESOURCE")
    if (stream == null) {
      // Not fatal: the app runs, and the update check declines to compare against a version it can't
      // read. Worth a line, because "no updates ever offered" otherwise looks like the check is broken.
      SharkLog.d { "No $VERSION_RESOURCE on the classpath, so this run has no version" }
      return UNKNOWN_VERSION
    }
    val version = stream.use { Properties().apply { load(it) }.getProperty("version") }
    return if (version.isNullOrBlank()) {
      SharkLog.d { "$VERSION_RESOURCE has no version in it" }
      UNKNOWN_VERSION
    } else {
      version
    }
  }

  /**
   * What a run whose version can't be read is called. Deliberately not a number: an unknown version must
   * not compare as older than a release and start offering an update to a build we know nothing about.
   */
  const val UNKNOWN_VERSION = "unknown"

  private const val VERSION_RESOURCE = "shark-explorer-version.properties"
}
