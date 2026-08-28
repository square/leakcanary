package shark.dive.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Desktop
import java.net.URI
import shark.SharkLog

/**
 * Whether this run has an update to tell someone about, shared by every window of it.
 *
 * One per run rather than one per window, because the check is about the app and not about a heap dump:
 * asking once is enough, and dismissing the bar in one window should not leave it up in the three others.
 *
 * Plain state rather than a composable's, for the same reason [DiveWindow] is: a `Window` needs a
 * display, so anything only reachable from inside one is untestable here. See AGENTS.md.
 */
internal class UpdateNotice {

  /** Null until the check has answered, and again once [dismiss] has been called. */
  var availableUpdate: AvailableUpdate? by mutableStateOf(null)
    private set

  fun offer(update: AvailableUpdate) {
    availableUpdate = update
  }

  /** For this run only. The next one asks again, which is what makes ignoring it once harmless. */
  fun dismiss() {
    SharkLog.d { "Dismissed the update to ${availableUpdate?.version}" }
    availableUpdate = null
  }
}

/**
 * Opens the release page in whatever the machine calls a browser.
 *
 * [Desktop] is not available on every JVM and every desktop session, and a button that silently does
 * nothing is the worst version of this, so a failure says the URL in the log — where someone can at least
 * read it back out.
 */
internal fun openInBrowser(url: String) {
  try {
    val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.BROWSE) }
    if (desktop == null) {
      SharkLog.d { "No browser to open, so $url was not opened" }
      return
    }
    SharkLog.d { "Opening $url" }
    desktop.browse(URI.create(url))
  } catch (throwable: Throwable) {
    SharkLog.d(throwable) { "Could not open $url" }
  }
}
