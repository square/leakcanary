package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import shark.AnalyzeHprof.Companion
import shark.AnalyzeHprof.Companion.MAPPING_OPTION_HELP
import shark.AnalyzeHprof.Companion.MAPPING_OPTION_NAMES
import shark.AnalyzeHprof.Companion.MAPPING_USAGE
import shark.DumpProcess.Companion.DEVICE_METAVAR
import shark.DumpProcess.Companion.DEVICE_OPTION_HELP
import shark.DumpProcess.Companion.DEVICE_OPTION_NAMES
import shark.DumpProcess.Companion.DEVICE_USAGE
import shark.DumpProcess.Companion.PROCESS_NAME_ARG_NAME
import shark.DumpProcess.Companion.PROCESS_NAME_HELP
import shark.SharkCli.Companion.SHARK_CLI_COMMAND
import shark.SharkCli.Companion.USAGE_HELP_TAG

class AnalyzeProcess : CliktCommand(
    name = COMMAND,
    help = "Dumps the heap for the provided partial $PROCESS_NAME_ARG_NAME, pulls the hprof file and analyzes it.",
    helpTags = mapOf(
        USAGE_HELP_TAG to "$SHARK_CLI_COMMAND $COMMAND [$DEVICE_USAGE $MAPPING_USAGE] $PROCESS_NAME_ARG_NAME"
    ),
    printHelpOnEmptyArgs = true
) {

  private val processName by argument(name = PROCESS_NAME_ARG_NAME, help = PROCESS_NAME_HELP)

  private val device by option(
      *DEVICE_OPTION_NAMES, metavar = DEVICE_METAVAR, help = DEVICE_OPTION_HELP
  )

  private val obfuscationMappingPath by option(
      names = *MAPPING_OPTION_NAMES, help = MAPPING_OPTION_HELP
  ).file()

  override fun run() {
    val heapDumpFile = DumpProcess.dumpHeap(processName, device)
    AnalyzeHprof.analyze(heapDumpFile, obfuscationMappingPath)
  }

  companion object {
    private const val COMMAND = "analyze-process"
  }

}