package shark.dive.app

import shark.dive.DeepLink
import shark.dive.agent.AgentCommandLine

/**
 * Whether the command line asked what this app takes, and what to exit with if it did. Null for every other
 * command line.
 *
 * **The one place that names every half of it.** A run opens windows and answers commands, and the halves of
 * the command line are read in different places — [DiveArguments] takes the window's, [cliExitCode] and
 * [headlessAgentExitCode] the rest — so this is the only reader that has to know there is more than one. Which
 * is what makes it worth a declaration rather than a line in any of them.
 *
 * **Both spellings, and that is not a nicety.** A program an agent has not been told about is one it types
 * `--help` at, and `-h` next. Neither was an option here, so both fell through to the parser and came back as
 * `Unknown option --help` followed by a usage line with no mention of the command surface in it — so the single
 * most likely command an agent can type answered that this surface does not exist.
 * `.claude/skills/shark-dive/SKILL.md` is the other way an agent is told, and it is a file somebody has to have
 * staged; this is the one the build carries.
 *
 * **Nothing here reaches a run**, and that is the point of answering it first: help is what somebody reads on
 * the machine where nothing is open, so a text that needed a run to print it would be help that fails exactly
 * where it is needed. [AgentCommandLine.CLI_OPTION] with nothing after it lands here too, since a command line
 * that names no command is a question about what the commands are.
 *
 * **The method for solving a leak is here too**, and that is what it is for: a text printed on demand is read
 * once by whoever wants it, where a text carried in an answer is read again by every call that gets one. See
 * [AgentCommandLine.LEAK_METHOD_OPTION].
 *
 * Answered before any logging is installed, and it ends with 0: whoever typed this asked a question and got the
 * answer, so there is nothing to put in a log file and nothing to fail about.
 */
internal fun helpExitCode(args: Array<String>): Int? {
  // On stdout, both of them, because the text is the whole of what the command was run for.
  if (AgentCommandLine.LEAK_METHOD_OPTION in args) {
    println(AgentCommandLine.leakMethod())
    return 0
  }
  val helpIndex = args.indexOfFirst { it in HELP_OPTIONS }
  if (helpIndex >= 0) {
    val commandName = args.commandNameAt(helpIndex)
    println(
      if (commandName == null) {
        help(commandToRunThis())
      } else {
        AgentCommandLine.commandHelp(command = commandToRunThis(), commandName = commandName)
      }
    )
    return 0
  }
  // Which is a command line saying it is a command and then naming none, so what it is asking is this.
  if (args.size == 1 && args.single() == AgentCommandLine.CLI_OPTION) {
    println(help(commandToRunThis()))
    return 0
  }
  return null
}

/**
 * What this app takes, as text, with [command] being what somebody types to run it.
 *
 * One document rather than a window's help and a command surface's help, because **a reader does not know
 * which half their question is in**: opening a heap dump is a command, having one open is a window, and the
 * two were two texts with two option columns and one of them reachable only by knowing the option that prints
 * it. So the options are one column, in the order somebody meets them, and [AgentCommandLine.commandsHelp]
 * carries the commands under it.
 */
private fun help(command: String): String = """
  |Shark Dive is a tool to explore heap dumps, using a UI and/or a CLI.
  |
  |  $command [<heap dump>…] [${DeepLink.SCHEME}://<heap dump>/<place>…]
  |  $command ${AgentCommandLine.CLI_OPTION} <command> name=value …
  |
  |${options()}
  |
  |${AgentCommandLine.commandsHelp(command)}
""".trimMargin()

/**
 * Every option, the window's and the command surface's, in the order somebody meets them.
 *
 * The window's are here and the rest come from [AgentCommandLine.cliOptions], each list beside the code that
 * reads it: an option described where it is not parsed is one that goes stale silently.
 *
 * **The `--debug-` ones are last, and that is the whole of what the prefix buys.** They are for working on
 * Shark Dive rather than on a heap dump — naming windows apart, and picking between two runs of it — so a
 * reader meets the two arguments that are a question about a heap dump first, and the commands after them,
 * and these when there is nothing else left to read. Last in the column and absent from the two lines above
 * it, since a synopsis is what somebody copies.
 */
private fun options(): String = (
  listOf(
    "<heap dump>" to
      "Opened as this starts, a window each unless ${AgentCommandLine.NO_UI_OPTION} says to draw none.",
    "${DeepLink.SCHEME}://<heap dump>/<place>" to
      "Goes to a place of a heap dump (a leak, an object, a tab) in whichever window has it open."
  ) + AgentCommandLine.cliOptions() + debugOptions()
  ).asOptionColumn()

/** For working on Shark Dive itself: the window's, then the command surface's. See [options]. */
private fun debugOptions(): List<Pair<String, String>> = listOf(
  "$TITLE_OPTION=<prefix>" to
    "In front of every window title of this run, so that two windows on one heap dump can be told apart."
) + AgentCommandLine.debugCliOptions()

/**
 * One option per row, its description in a column beside it, wrapped rather than run on.
 *
 * Wrapped because one description three times the width of the others is a column that reads as broken, and
 * the one that needs the room is the one an agent most needs to read: which session to say it is part of.
 */
private fun List<Pair<String, String>>.asOptionColumn(): String {
  val continuation = " ".repeat(INDENT.length + OPTION_WIDTH)
  return flatMap { (option, what) ->
    what.wrappedAt(AgentCommandLine.HELP_WIDTH - continuation.length).mapIndexed { index, line ->
      if (index == 0) "$INDENT${option.padEnd(OPTION_WIDTH)}$line" else "$continuation$line"
    }
  }.joinToString("\n")
}

/** This text in lines of at most [width] characters, broken at spaces, a longer word left long. */
private fun String.wrappedAt(width: Int): List<String> {
  val lines = mutableListOf<String>()
  val line = StringBuilder()
  split(' ').forEach { word ->
    if (line.isNotEmpty() && line.length + 1 + word.length > width) {
      lines += line.toString()
      line.clear()
    }
    if (line.isNotEmpty()) {
      line.append(' ')
    }
    line.append(word)
  }
  return lines + line.toString()
}

/**
 * What a command line says to ask what it takes.
 *
 * Two of them, since an agent that guessed wrong at the first guesses the other and a program that answers one
 * and not the other reads as a program with no help at all.
 */
private val HELP_OPTIONS = setOf(AgentCommandLine.HELP_OPTION, "-h")

/**
 * Wide enough for the longest option above, since the descriptions read as a column or as nothing.
 *
 * Which is `--debug-title-prefix=<prefix>` at 29, so this is that and a gap. `CliOptionsTest` fails on an
 * option that outgrows it, rather than leaving a help text whose longest row has its description jammed
 * against it.
 */
private const val OPTION_WIDTH = 31

private const val INDENT = "  "
