package shark.dive.app

import java.awt.Desktop
import java.awt.Desktop.Action.APP_OPEN_FILE
import shark.SharkLog

/**
 * Taking the `.hprof` files the OS hands this app, which is a heap dump double clicked in the file manager
 * or opened with **Open with → Shark Dive**.
 *
 * **Telling the OS is the build script's job, on all three platforms** — `fileAssociation` on each
 * `nativeDistributions` block, which becomes `CFBundleDocumentTypes` in the macOS bundle, a registry entry
 * in the `.msi` and a MIME type with a `.desktop` entry in the `.deb`. Nothing is registered at runtime,
 * unlike [DeepLinkScheme], which has to because no packaging format declares a URL scheme for Linux.
 *
 * **Taking the file splits the platforms the same way a link does, and for the same reason.** Windows and
 * Linux start a process with the path on its command line, which is [DiveArguments] and the path every run
 * of this app from a terminal already takes. macOS starts no process for an app that is already running: it
 * sends an Apple Event, which AWT turns into the handler installed here. So without this, a heap dump
 * double clicked while Shark Dive is up does nothing at all, and one double clicked while it is down opens
 * a second copy of the app showing no heap dump.
 *
 * Best effort, like the scheme: a platform that hands no file to a running process says so in the log and
 * the app goes on working.
 */
internal object HeapDumpAssociation {

  /**
   * Takes the heap dumps macOS hands to this process, which is every `.hprof` opened from the file manager
   * while it is running and the one that started it.
   *
   * A file that arrives before this is installed is not lost: AWT holds the event until there is a handler
   * for it, which is what makes a heap dump that launches the app open in the window that launch created.
   * Measured, not assumed — see the module's AGENTS.md.
   *
   * Several files at once is one event, which is selecting two heap dumps and opening both. Each is a window,
   * the same way two paths on the command line are.
   */
  fun takeFilesFromTheOs(windows: DiveWindows) {
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(APP_OPEN_FILE)) {
      // Windows and Linux, where the OS starts a process per file instead. Said in the log because this is
      // also what a macOS build that has lost its CFBundleDocumentTypes looks like.
      SharkLog.d { "This OS hands no files to a running process, so heap dumps arrive on the command line" }
      return
    }
    Desktop.getDesktop().setOpenFileHandler { event ->
      // Logged per file before anything is done with it, so that a heap dump the OS delivered and the app
      // then failed to open is told apart from one the OS never delivered.
      event.files.forEach { file ->
        SharkLog.d { "The OS handed this run \"$file\" to open" }
        windows.openHeapDumpFromTheOs(file)
      }
    }
    SharkLog.d { "Taking the $HEAP_DUMP_EXTENSION files the OS hands to this run" }
  }
}

/**
 * What a heap dump is called, which is what the OS was told this app opens and what the file picker shows.
 *
 * Outside the object because the picker in the bar filters by the same thing, and a picker offering a set of
 * files the OS does not hand over would be two answers to "which files are heap dumps". The build script
 * spells it a third time — jpackage takes an extension without the dot — and cannot read this.
 */
internal const val HEAP_DUMP_EXTENSION = ".hprof"
