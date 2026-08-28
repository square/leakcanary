package shark.dive.app

import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.Properties
import shark.SharkLog

/** A release newer than this run, as [UpdateCheck] found it. */
internal data class AvailableUpdate(
  val version: String,
  /** The release page, which is where the download buttons and the release notes are. */
  val releaseUrl: String
)

/**
 * Asks whether a newer Shark Dive has been released, and answers with [AvailableUpdate] or null.
 *
 * **It only ever reports. Nothing here downloads or installs anything** — the release page opens in a
 * browser and the rest is the person's to do. A JVM app can't replace itself while it runs without a
 * native helper, and an app that rewrites its own signed bundle is a much bigger thing to get right than
 * a link is.
 *
 * Plain logic behind a `fetchManifest` function so that all of it is unit testable: the comparison, the
 * parsing and every way the manifest can be unusable are what go wrong here, and none of them needs a
 * network.
 */
internal class UpdateCheck(
  private val currentVersion: String = SharkDiveVersion.current,
  private val fetchManifest: () -> String? = ::fetchLatestManifest
) {

  fun check(): AvailableUpdate? {
    if (currentVersion == SharkDiveVersion.UNKNOWN_VERSION) {
      // A run that doesn't know its own version can't tell a newer release from the one it is, and
      // offering an update to every run of a development build would be worse than offering none.
      SharkLog.d { "Not checking for updates: this run has no version" }
      return null
    }
    val manifest = try {
      fetchManifest()
    } catch (throwable: Throwable) {
      // Offline, behind a proxy, or GitHub is down. None of that is the app's problem to solve, and none
      // of it should reach a window, so it goes in the log and the check is simply over for this run.
      SharkLog.d(throwable) { "Could not fetch $LATEST_MANIFEST_URL" }
      return null
    }
    if (manifest == null) {
      SharkLog.d { "No release manifest at $LATEST_MANIFEST_URL" }
      return null
    }
    val update = parseReleaseManifest(manifest)
    if (update == null) {
      SharkLog.d { "Could not read a version and a release URL out of the manifest" }
      return null
    }
    val newer = isNewerVersion(update.version, currentVersion)
    SharkLog.d {
      "Latest release is ${update.version}, this run is $currentVersion" +
        if (newer) ", so there is an update" else ", which is up to date"
    }
    return if (newer) update else null
  }
}

/**
 * Reads the manifest, which is a `.properties` file holding the released version and its release page.
 *
 * Properties rather than the JSON every comparable updater uses, because `java.util.Properties` parses
 * it and JSON would mean a dependency this app has no other use for. The release workflow writes it, so
 * both ends of the format are in this repo.
 *
 * Null for anything that isn't a manifest, which includes the HTML GitHub serves when the rolling tag
 * doesn't exist yet: a `Properties` load of a web page succeeds and simply has no `version` in it.
 */
internal fun parseReleaseManifest(manifest: String): AvailableUpdate? {
  val properties = try {
    Properties().apply { StringReader(manifest).use { load(it) } }
  } catch (throwable: Throwable) {
    SharkLog.d(throwable) { "Release manifest is not a properties file" }
    return null
  }
  val version = properties.getProperty("version")?.trim()
  val releaseUrl = properties.getProperty("releaseUrl")?.trim()
  return if (version.isNullOrEmpty() || releaseUrl.isNullOrEmpty()) {
    null
  } else {
    AvailableUpdate(version, releaseUrl)
  }
}

/**
 * Whether [candidate] is a later version than [current], both `MAJOR.MINOR.PATCH` — which is the only
 * shape either can be, since jpackage builds no other.
 *
 * A missing component counts as 0, so `0.2` is later than `0.1.9` and the same as `0.2.0`. Anything
 * non-numeric makes this false rather than throwing: a manifest we can't read is not grounds for telling
 * someone their app is out of date.
 */
internal fun isNewerVersion(
  candidate: String,
  current: String
): Boolean {
  val candidateParts = versionParts(candidate) ?: return false
  val currentParts = versionParts(current) ?: return false
  val componentCount = maxOf(candidateParts.size, currentParts.size)
  for (index in 0 until componentCount) {
    val candidatePart = candidateParts.getOrElse(index) { 0 }
    val currentPart = currentParts.getOrElse(index) { 0 }
    if (candidatePart != currentPart) {
      return candidatePart > currentPart
    }
  }
  return false
}

/** Null for anything that isn't dot separated non-negative integers, which is every version we build. */
private fun versionParts(version: String): List<Int>? {
  val parts = version.trim().split(".")
  return parts.map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
}

/**
 * Fetches the manifest, or null for any response that isn't one.
 *
 * A file off the release download CDN rather than the GitHub API, for two reasons. The API's
 * `releases/latest` is **the wrong release** — this repo publishes LeakCanary library releases on `v*`
 * tags and Shark Dive on `shark-dive-*` tags, and GitHub has one "latest" per repository, so that
 * endpoint answers with whichever came last overall. And the unauthenticated API allows 60 requests an
 * hour *per IP*, which is a shared corporate egress away from being exhausted by other people's runs,
 * while a release asset is an ordinary unmetered CDN download.
 *
 * The other half of that choice is the [LATEST_MANIFEST_URL] tag being moved deliberately, so publishing
 * a build and telling everyone about it stay two separate acts. See docs/releasing-shark-dive.md.
 */
private fun fetchLatestManifest(): String? {
  val client = HttpClient.newBuilder()
    .connectTimeout(REQUEST_TIMEOUT)
    // GitHub answers a release download with a redirect to the CDN it is actually served from.
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()
  val request = HttpRequest.newBuilder(URI.create(LATEST_MANIFEST_URL))
    .timeout(REQUEST_TIMEOUT)
    .header("Accept", "text/plain")
    .GET()
    .build()
  // Not closed, because HttpClient only became AutoCloseable in Java 21 and this module targets 17. One
  // client per check, and the check runs once a session.
  val response = client.send(request, BodyHandlers.ofString())
  return if (response.statusCode() == HTTP_OK) {
    response.body()
  } else {
    // 404 until the first release has moved the rolling tag, which is the expected answer for a while.
    SharkLog.d { "$LATEST_MANIFEST_URL answered ${response.statusCode()}" }
    null
  }
}

/**
 * The one asset of the rolling `shark-dive-latest` release, rewritten by whichever release is being
 * offered to everyone. Not necessarily the newest one that exists — that is the point of it.
 */
private const val LATEST_MANIFEST_URL =
  "https://github.com/square/leakcanary/releases/download/shark-dive-latest/latest.properties"

private const val HTTP_OK = 200

/** Short: this runs while someone waits for a window, and a check that never finishes just never fires. */
private val REQUEST_TIMEOUT = Duration.ofSeconds(10)
