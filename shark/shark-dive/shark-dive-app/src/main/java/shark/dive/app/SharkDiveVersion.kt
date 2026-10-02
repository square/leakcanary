package shark.dive.app

import java.util.Properties
import shark.SharkLog

/**
 * Which build of Shark Dive this is, in the two ways something needs to know.
 *
 * Read off the classpath from a file the build script generates out of `SHARK_DIVE_VERSION` — see
 * `writeVersionResource` — rather than from the jar manifest, so that `./gradlew run` and the tests
 * report the same version a packaged build does.
 */
internal object SharkDiveVersion {

  /**
   * The release this is, which is what [UpdateCheck] compares a release against.
   *
   * [UNKNOWN_VERSION] when the resource is missing, which is a classpath built by hand.
   */
  val current: String by lazy { property(VERSION_PROPERTY) }

  /**
   * The commit it was built from, which is what a command line only talks to a run of its own build by.
   *
   * A different question from [current], and the reason it is a second property rather than part of the
   * version: two builds of one release differ in every command they have while this surface is being worked
   * on, and a machine in the middle of a branch has last week's run still up. See
   * `shark.dive.agent.AgentServer.PublishedRun.buildSha`.
   */
  val buildSha: String by lazy { property(BUILD_SHA_PROPERTY) }

  /**
   * One property of the generated resource, read per call.
   *
   * Which is two reads of a file of two lines, for two values read once each as a run starts: a `Properties`
   * held between them would be state shared by everything that asks, for a saving of nothing.
   */
  private fun property(name: String): String {
    val stream = javaClass.getResourceAsStream("/$VERSION_RESOURCE")
    if (stream == null) {
      // Not fatal: the app runs, and the update check declines to compare against a version it can't
      // read. Worth a line, because "no updates ever offered" otherwise looks like the check is broken.
      SharkLog.d { "No $VERSION_RESOURCE on the classpath, so this run has no $name" }
      return UNKNOWN_VERSION
    }
    val value = stream.use { Properties().apply { load(it) }.getProperty(name) }
    return if (value.isNullOrBlank()) {
      SharkLog.d { "$VERSION_RESOURCE has no $name in it" }
      UNKNOWN_VERSION
    } else {
      value
    }
  }

  /**
   * What a run whose version can't be read is called. Deliberately not a number: an unknown version must
   * not compare as older than a release and start offering an update to a build we know nothing about.
   *
   * And the same word for a build sha nothing could read, where what it costs is the opposite of a risk: two
   * runs of a checkout with no git directory match each other, which is what a command line reaching a run
   * built the same way as itself needs.
   */
  const val UNKNOWN_VERSION = "unknown"

  private const val VERSION_RESOURCE = "shark-dive-version.properties"
  private const val VERSION_PROPERTY = "version"
  private const val BUILD_SHA_PROPERTY = "buildSha"
}
