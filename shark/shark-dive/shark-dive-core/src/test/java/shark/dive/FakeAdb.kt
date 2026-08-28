package shark.dive

/**
 * An `adb` that answers what it was told to, and records what it was asked.
 *
 * Keyed on the start of the arguments joined by spaces, which is how the command reads in a shell and
 * therefore how a test can be read against it. The start rather than the whole of it, because some of
 * these commands name a file whose name has the time it was made in it.
 *
 * A command nothing was said about is an empty success rather than a failure, so that a test states the
 * answers it cares about and no others — [commands] is there for asserting on what was run.
 */
class FakeAdb(private val answers: Map<String, (List<String>) -> AdbOutput>) : Adb {

  constructor(vararg answers: Pair<String, String>) : this(
    answers.associate { (command, text) -> command to { _: List<String> -> AdbOutput(0, text) } }
  )

  private val recorded = mutableListOf<String>()

  /** Every command run so far, joined by spaces, in the order they were run. */
  val commands: List<String> get() = recorded

  override fun run(arguments: List<String>): AdbOutput {
    val command = arguments.joinToString(" ")
    recorded += command
    val answer = answers.entries.firstOrNull { command.startsWith(it.key) }
    return answer?.value?.invoke(arguments) ?: AdbOutput(exitCode = 0, text = "")
  }
}
