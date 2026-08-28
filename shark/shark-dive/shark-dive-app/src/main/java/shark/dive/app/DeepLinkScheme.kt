package shark.dive.app

import java.awt.Desktop
import java.awt.Desktop.Action.APP_OPEN_URI
import java.awt.Desktop.Action.APP_REQUEST_FOREGROUND
import java.io.File
import java.util.concurrent.TimeUnit
import shark.SharkLog
import shark.dive.DeepLink

/**
 * Telling the OS that `shark://` links are this app's, and taking the ones it hands over.
 *
 * The three platforms split in two here. **macOS is told at build time** — `CFBundleURLTypes` in the
 * `Info.plist` the Compose plugin writes, see this module's build script — and hands a URL to the process
 * that is already running, through an Apple Event that AWT turns into [Desktop.setOpenURIHandler]. Nothing
 * has to be registered at runtime and no second process is started.
 *
 * **Windows and Linux have no such thing in the packaging**, so the app registers itself the first time it
 * runs, and a link there launches a whole new process with the URL on its command line. Reaching the run
 * that has the window is then [DeepLinkPeers]' job.
 *
 * All of it is best effort, and every failure is one line in the log rather than anything the reader is
 * asked to deal with: an app that opens heap dumps and doesn't answer links is worth having, and one that
 * refuses to start because a registry key wouldn't take is not.
 */
internal object DeepLinkScheme {

  /**
   * Takes the URLs macOS hands to this process, which is every `shark://` link followed while it is running
   * and the one that started it.
   *
   * A link that arrives before this is installed is not lost: AWT holds the event until there is a handler
   * for it, which is what makes a link that launches the app work without a second path for it. Measured,
   * not assumed — see the module's AGENTS.md.
   */
  fun takeUrisFromTheOs(windows: DiveWindows) {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(APP_OPEN_URI)) {
      // Windows and Linux, where the OS starts a process per link instead. Said in the log because this is
      // also what a macOS build that has lost its Info.plist entry looks like.
      SharkLog.d { "This OS hands no URLs to a running process, so links arrive on the command line" }
      return
    }
    Desktop.getDesktop().setOpenURIHandler { event ->
      val uri = event.uri.toString()
      SharkLog.d { "The OS handed this run \"$uri\"" }
      val link = try {
        DeepLink.parse(uri)
      } catch (invalidLink: IllegalArgumentException) {
        SharkLog.d { "Which is no link: ${invalidLink.message}" }
        return@setOpenURIHandler
      }
      // Not straight to a window of this run: macOS hands every `shark://` link on the machine to the one
      // installed app, and the window named may belong to another run — one from source, most of all.
      DeepLinkPeers.follow(link, windows)
    }
    SharkLog.d { "Taking ${DeepLink.SCHEME}:// links the OS hands to this run" }
  }

  /**
   * Puts this process in front of the others, which a window raising itself does not do on macOS.
   *
   * `toFront()` orders the windows of an application; which application is in front is the process's to ask
   * for, and this is how it asks. Verified to raise the app even when the OS was told to open the link
   * without activating it.
   */
  fun bringProcessToFront() {
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(APP_REQUEST_FOREGROUND)) {
        Desktop.getDesktop().requestForeground(true)
      }
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not bring this run to the front" }
    }
  }

  /**
   * Registers `shark://` with Windows or Linux, off the thread that is opening a window.
   *
   * Only from a packaged build: the launcher a link would be started with has to be this app, and a run
   * from Gradle or an IDE is the JVM with a classpath, which nothing can usefully write into a registry key
   * or a desktop entry. Such a run still *answers* links — see [DeepLinkPeers] — it just isn't what the OS
   * starts to deliver one.
   */
  fun registerWithTheOs() {
    val launcher = launcherPathOrNull()
    if (launcher == null) {
      SharkLog.d {
        "Not registering ${DeepLink.SCHEME}:// with the OS: this run is a JVM on a classpath rather than " +
          "a packaged app, so there is no launcher to register"
      }
      return
    }
    Thread({
      try {
        when {
          isWindows() -> registerOnWindows(launcher)
          isLinux() -> registerOnLinux(launcher)
          // macOS is told by the Info.plist of the bundle, at build time.
          else -> Unit
        }
      } catch (throwable: Throwable) {
        SharkLog.d(throwable) { "Could not register ${DeepLink.SCHEME}:// with the OS" }
      }
    }, "shark-dive-scheme").apply {
      isDaemon = true
      start()
    }
  }

  private fun registerOnWindows(launcher: String) {
    val command = "\"$launcher\" \"%1\""
    if (run("reg", "query", COMMAND_KEY, "/ve").contains(launcher)) {
      SharkLog.d { "${DeepLink.SCHEME}:// already opens $launcher" }
      return
    }
    run("reg", "add", SCHEME_KEY, "/ve", "/d", "URL:$APP_NAME", "/f")
    run("reg", "add", SCHEME_KEY, "/v", "URL Protocol", "/d", "", "/f")
    run("reg", "add", COMMAND_KEY, "/ve", "/d", command, "/f")
    SharkLog.d { "Registered ${DeepLink.SCHEME}:// to open $launcher" }
  }

  private fun registerOnLinux(launcher: String) {
    val applications = File(System.getProperty("user.home"), ".local/share/applications")
    val desktopFile = File(applications, DESKTOP_FILE_NAME)
    val entry = desktopEntry(launcher)
    if (desktopFile.isFile && desktopFile.readText() == entry) {
      SharkLog.d { "${DeepLink.SCHEME}:// already opens $launcher" }
      return
    }
    applications.mkdirs()
    desktopFile.writeText(entry)
    // Both are best effort and neither exists everywhere: a desktop that reads the directory itself needs
    // no database, and one with no xdg-utils installed has no xdg-mime to make this the default handler.
    run("update-desktop-database", applications.path)
    run("xdg-mime", "default", DESKTOP_FILE_NAME, "x-scheme-handler/${DeepLink.SCHEME}")
    SharkLog.d { "Registered ${DeepLink.SCHEME}:// in $desktopFile to open $launcher" }
  }

  /**
   * Hidden from the menu, because a packaged install already has a visible entry of its own from its `.deb`
   * and two of them is one too many. A hidden entry is still a handler.
   */
  private fun desktopEntry(launcher: String): String =
    """
    [Desktop Entry]
    Type=Application
    Name=$APP_NAME
    Exec="$launcher" %u
    Terminal=false
    NoDisplay=true
    MimeType=x-scheme-handler/${DeepLink.SCHEME};
    """.trimIndent() + "\n"

  /** What the command printed, or empty for one that failed or isn't installed. */
  private fun run(vararg command: String): String = try {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroy()
    }
    output
  } catch (throwable: Throwable) {
    SharkLog.d { "${command.first()} could not be run: ${throwable.message}" }
    ""
  }

  private fun isWindows(): Boolean = osName().startsWith("windows")

  private fun isLinux(): Boolean = osName().startsWith("linux")

  private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()

  private val SCHEME_KEY = "HKCU\\Software\\Classes\\${DeepLink.SCHEME}"
  private val COMMAND_KEY = "$SCHEME_KEY\\shell\\open\\command"

  private const val DESKTOP_FILE_NAME = "shark-dive.desktop"
  private const val COMMAND_TIMEOUT_SECONDS = 10L
}
