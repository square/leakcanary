package shark.dive.app

import shark.dive.DeepLink
import shark.dive.agent.AgentCommandLine

/**
 * Whether the command line asked what this app takes, and what to exit with if it did. Null for every other
 * command line.
 *
 * **The one place that names both halves of it.** A run opens windows and answers agents, and the two halves
 * of the command line are read in different places — [DiveArguments] takes the window's, [agentCommandExitCode]
 * and [headlessAgentExitCode] the agent's — so this is the only reader that has to know there are two. Which
 * is what makes it worth a declaration rather than a line in either.
 *
 * **Both spellings, and that is not a nicety.** A program an agent has not been told about is one it types
 * `--help` at, and `-h` next. Neither was an option here, so both fell through to the parser and came back as
 * `Unknown option --help` followed by a usage line with no mention of `--agent`, `--agent-help` or
 * [NO_UI_OPTION] in it — so the single most likely command an agent can type answered that this surface does
 * not exist. `.claude/skills/shark-dive/SKILL.md` is the other way an agent is told, and it is a file somebody
 * has to have staged; this is the one the build carries.
 *
 * Answered before any logging is installed, and it ends with 0: whoever typed this asked a question and got
 * the answer, so there is nothing to put in a log file and nothing to fail about.
 */
internal fun helpExitCode(args: Array<String>): Int? {
  if (args.none { it in HELP_OPTIONS }) {
    return null
  }
  // On stdout, because it is the whole of what the command was run for. See [AgentCommandLine.help], which is
  // the same choice for the same reason and the text this one points at.
  println(help(commandToRunThis()))
  return 0
}

/** What this app takes, as text, with [command] being what somebody types to run it. */
private fun help(command: String): String = """
  |Shark Dive reads a heap dump in a window, and answers agents about whatever it has open.
  |
  |  $command [$TITLE_OPTION="<window title prefix>"] [<heap dump>…] [${DeepLink.SCHEME}://<heap dump>/<place>…]
  |
  |${windowOptions()}
  |
  |AGENTS
  |
  |One call per command, answered by the run that already has the heap dump open, so that an agent and the
  |person watching it are reading the same window. ${AgentCommandLine.HELP_OPTION} is where to start, and it
  |needs no run and no heap dump: it is text this build carries.
  |
  |${agentOptions()}
""".trimMargin()

private fun windowOptions(): String = listOf(
  "$TITLE_OPTION=<prefix>" to
    "In front of every window title of this run, so that two windows on one heap dump can be told apart.",
  "<heap dump>" to "One window each, opened as this starts.",
  "${DeepLink.SCHEME}://<heap dump>/<place>" to
    "Goes to a place of a heap dump — a leak, an object, a tab — in whichever window has it open."
).asOptionColumn()

private fun agentOptions(): String = listOf(
  "${AgentCommandLine.AGENT_OPTION} <tool> name=value" to
    "Makes one call and prints the answer as JSON. Opens a window when no run is open.",
  AgentCommandLine.HELP_OPTION to "Every tool there is, with what it is for and the arguments it takes.",
  "${AgentCommandLine.HELP_OPTION} <tool>" to "Just that one.",
  "${AgentCommandLine.PID_OPTION}<pid>" to "Which run to call, when more than one is open.",
  "${AgentCommandLine.SESSION_OPTION}<name>" to
    "Which session these calls are one of, on the *Agent logs* screen of the window.",
  NO_UI_OPTION to "A run that answers agents and opens no window, for a machine with no screen."
).asOptionColumn()

private fun List<Pair<String, String>>.asOptionColumn(): String =
  joinToString("\n") { (option, what) -> "  ${option.padEnd(OPTION_WIDTH)}$what" }

/**
 * What a command line says to ask what it takes.
 *
 * Two of them, since an agent that guessed wrong at the first guesses the other and a program that answers one
 * and not the other reads as a program with no help at all.
 */
private val HELP_OPTIONS = setOf("--help", "-h")

/** Wide enough for the longest option above, since the descriptions read as a column or as nothing. */
private const val OPTION_WIDTH = 29
