package shark

import com.github.ajalt.clikt.core.CliktCommand
import shark.SharkLog.Logger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit.SECONDS

class SharkCli : CliktCommand(
    name = SHARK_CLI_COMMAND,
    // This ASCII art is a remix of a shark from -David "TAZ" Baltazar- and chick from jgs.
    help = """
    |```
    |$S                ^`.                 .=""=.
    |$S^_              \  \               / _  _ \
    |$S\ \             {   \             |  d  b  |
    |$S{  \           /     `~~~--__     \   /\   /
    |$S{   \___----~~'              `~~-_/'-=\/=-'\,
    |$S \                         /// a  `~.      \ \
    |$S / /~~~~-, ,__.    ,      ///  __,,,,)      \ |
    |$S \/      \/    `~~~;   ,---~~-_`/ \        / \/
    |$S                  /   /            '.    .'
    |$S                 '._.'             _|`~~`|_
    |$S                                   /|\  /|\
    |```
    """.trimMargin()

) {

  override fun run() {
    class CLILogger : Logger {

      override fun d(message: String) {
        echo(message)
      }

      override fun d(
        throwable: Throwable,
        message: String
      ) {
        d("$message\n${getStackTraceString(throwable)}")
      }

      private fun getStackTraceString(throwable: Throwable): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter, false)
        throwable.printStackTrace(printWriter)
        printWriter.flush()
        return stringWriter.toString()
      }
    }

    SharkLog.logger = CLILogger()

  }

  companion object {

    const val SHARK_CLI_COMMAND = "shark-cli"

    const val USAGE_HELP_TAG = "Usage"

    /** Zero width space */
    private const val S = '\u200b'

    fun runCommand(
      directory: File,
      vararg arguments: String
    ): String {
      val process = ProcessBuilder(*arguments)
          .directory(directory)
          .start()
          .also { it.waitFor(10, SECONDS) }

      if (process.exitValue() != 0) {
        throw Exception(
            "Failed command: '${arguments.joinToString(
                " "
            )}', error output: '${process.errorStream.bufferedReader().readText()}'"
        )
      }
      return process.inputStream.bufferedReader()
          .readText()
    }

  }
}