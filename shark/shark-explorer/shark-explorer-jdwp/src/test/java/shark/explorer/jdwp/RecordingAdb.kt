package shark.explorer.jdwp

import shark.explorer.Adb
import shark.explorer.AdbOutput

/** An [Adb] that answers by command prefix and remembers what it was asked. */
class RecordingAdb(private val answers: Map<String, String>) : Adb {

  private val recorded = mutableListOf<String>()

  val commands: List<String> get() = recorded

  override fun run(arguments: List<String>): AdbOutput {
    val command = arguments.joinToString(" ")
    recorded += command
    val answer = answers.entries.firstOrNull { command.startsWith(it.key) }?.value.orEmpty()
    return AdbOutput(exitCode = 0, text = answer)
  }
}
