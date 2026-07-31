package shark.explorer.app

import java.io.File

/**
 * What the command line asked this run to do: which heap dumps to open, and what to call its windows.
 *
 * Plain state parsed from the arguments, so what a command line means is unit tested rather than found
 * out by launching the app.
 */
internal data class ExplorerArguments(
  /** One window each, in the order they were named. Empty for a run that opens no heap dump. */
  val heapDumpFiles: List<File>,
  /**
   * Put in front of every window title of this run, or null when the command line asked for none.
   *
   * Several explorers open at once look alike in the window list — same app, often the same heap dump,
   * opened for different reasons — and a title is all the OS gives you to pick one by. So a run started
   * for a piece of work can say which work it is: "the window titled *Hover previews* is the one with the
   * change in it".
   */
  val titlePrefix: String?
) {

  companion object {

    /**
     * Reads a command line, or throws [IllegalArgumentException] naming what is wrong with it.
     *
     * Strict about options rather than lenient: an unknown one taken for a file name opens a window
     * saying a heap dump called `--titel=Hover` could not be read, which is a typo reported as a
     * missing heap dump.
     */
    fun parse(args: List<String>): ExplorerArguments {
      var titlePrefix: String? = null
      val heapDumpFiles = mutableListOf<File>()
      val remaining = ArrayDeque(args)
      while (remaining.isNotEmpty()) {
        val argument = remaining.removeFirst()
        titlePrefix = when {
          // Both spellings, because a title has spaces in it and which one survives the shell, Gradle's
          // `--args` and an IDE run configuration is not the same everywhere.
          argument == TITLE_OPTION -> requireNotNull(remaining.removeFirstOrNull()) {
            "$TITLE_OPTION needs a title after it. $USAGE"
          }
          argument.startsWith("$TITLE_OPTION=") -> argument.substringAfter('=')
          argument.startsWith("-") -> throw IllegalArgumentException("Unknown option $argument. $USAGE")
          else -> {
            heapDumpFiles += File(argument)
            titlePrefix
          }
        }
      }
      require(titlePrefix != "") { "$TITLE_OPTION was given nothing to call the windows. $USAGE" }
      return ExplorerArguments(heapDumpFiles = heapDumpFiles, titlePrefix = titlePrefix)
    }

    private const val TITLE_OPTION = "--title"

    /** Shown with whatever was wrong, so that the message says what to type instead. */
    private const val USAGE =
      "Usage: shark-explorer [$TITLE_OPTION=\"<window title prefix>\"] [<heap dump>…]"
  }
}
