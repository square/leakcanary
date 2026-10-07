package shark.dive.app

import java.awt.Desktop
import java.io.IOException
import java.net.URI
import shark.SharkLog

/**
 * Hands [url] to the OS, which opens it with whichever app has its scheme: a browser for the release page and
 * for a web link in a note, Android Studio for an `idea://` link to a line of source.
 *
 * **Not through [Desktop.browse] on macOS.** AWT's browse opens a URL *in the default browser* whatever its
 * scheme: `CDesktopPeer.m` asks LaunchServices which app opens `https://` and hands the URL to that one.
 * Measured on Temurin 17.0.19 with a scheme registered to a test app: `open` reached the app, browse never did,
 * and browse returned as if it had worked. `/usr/bin/open` is LaunchServices choosing the app by the URL's own
 * scheme, which is the app the link names. Every URL goes that way on macOS, a web link included, so there is
 * one way to open a link per OS rather than one per scheme.
 *
 * Windows is left on [Desktop.browse], which has the same flaw there: `WDesktopPeer` starts the default browser
 * with the URL. Android Studio registers no URL scheme on Windows, and nothing here has been tried on it. Linux's
 * browse is GIO's, which already chooses the app by scheme.
 *
 * [Desktop] is not available on every JVM and every desktop session, and a button that silently does nothing is
 * the worst version of this, so a failure says the URL in the log, where someone can at least read it back out.
 */
internal fun openWithTheOs(url: String) {
  SharkLog.d { "Opening $url" }
  if (isMacOs()) {
    openWithLaunchServices(url)
    return
  }
  try {
    val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.BROWSE) }
    if (desktop == null) {
      SharkLog.d { "Nothing on this machine opens URLs, so $url was not opened" }
      return
    }
    desktop.browse(URI.create(url))
  } catch (throwable: Throwable) {
    SharkLog.d(throwable) { "Could not open $url" }
  }
}

/**
 * `open <url>`, with what it said logged when it failed.
 *
 * Its exit code is the one place a link nothing on this machine has an app for is told apart from one that
 * opened: `open` refuses it, with `kLSApplicationNotFoundErr`. Read once it has exited rather than waited for,
 * since this runs on the composition's thread and `open` takes a tenth of a second to answer.
 */
private fun openWithLaunchServices(url: String) {
  val process = try {
    ProcessBuilder(OPEN_COMMAND, url).redirectErrorStream(true).start()
  } catch (notStarted: IOException) {
    SharkLog.d(notStarted) { "Could not open $url" }
    return
  }
  process.onExit().thenAccept { exited ->
    if (exited.exitValue() != 0) {
      val said = exited.inputStream.bufferedReader().readText().trim()
      SharkLog.d { "Could not open $url: $said" }
    }
  }
}

private fun isMacOs(): Boolean = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")

private const val OPEN_COMMAND = "/usr/bin/open"
