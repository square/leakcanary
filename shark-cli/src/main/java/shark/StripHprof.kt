package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.file
import shark.AnalyzeHprof.Companion.HPROF_ARG_NAME
import shark.AnalyzeHprof.Companion.HPROF_HELP
import shark.SharkCli.Companion.SHARK_CLI_COMMAND
import shark.SharkCli.Companion.USAGE_HELP_TAG
import java.io.File

class StripHprof : CliktCommand(
    name = COMMAND,
    help = "Replaces all primitive arrays from the provided $HPROF_ARG_NAME with arrays of zeroes and generates a new \"-stripped.hprof\" file.",
    helpTags = mapOf(USAGE_HELP_TAG to "$SHARK_CLI_COMMAND $COMMAND $HPROF_ARG_NAME"),
    printHelpOnEmptyArgs = true
) {

  private val heapDumpFile by argument(name = HPROF_ARG_NAME, help = HPROF_HELP).file()

  override fun run() {
    stripHprof(heapDumpFile)
  }

  companion object {

    private const val COMMAND = "strip-hprof"

    private fun stripHprof(heapDumpFile: File) {
      SharkLog.d { "Stripping primitive arrays in heap dump $heapDumpFile" }
      val stripper = HprofPrimitiveArrayStripper()
      val outputFile = stripper.stripPrimitiveArrays(heapDumpFile)
      SharkLog.d { "Stripped primitive arrays to $outputFile" }
    }
  }
}