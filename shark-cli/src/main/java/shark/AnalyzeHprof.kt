package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import shark.SharkCli.Companion.SHARK_CLI_COMMAND
import shark.SharkCli.Companion.USAGE_HELP_TAG
import java.io.File

class AnalyzeHprof : CliktCommand(
    name = COMMAND,
    help = "Analyzes the provided $HPROF_ARG_NAME.",
    helpTags = mapOf(
        USAGE_HELP_TAG to "$SHARK_CLI_COMMAND $COMMAND [$MAPPING_USAGE] $HPROF_ARG_NAME"
    ),
    printHelpOnEmptyArgs = true
) {

  private val heapDumpFile by argument(name = HPROF_ARG_NAME, help = HPROF_HELP).file()

  private val obfuscationMappingPath by option(
      names = *MAPPING_OPTION_NAMES, help = MAPPING_OPTION_HELP
  ).file()

  override fun run() {
    analyze(heapDumpFile, obfuscationMappingPath)
  }

  companion object {
    private const val COMMAND = "analyze-hprof"

    const val HPROF_ARG_NAME = "HPROF_FILE_PATH"
    const val HPROF_HELP = "Path to a .hprof file"

    val MAPPING_OPTION_NAMES = arrayOf("-m", "--obfuscation-mapping")
    const val MAPPING_OPTION_HELP = "path to obfuscation mapping file"
    const val MAPPING_USAGE = "--obfuscation-mapping PATH"

    fun analyze(
      heapDumpFile: File,
      proguardMappingFile: File?
    ) {
      val listener = OnAnalysisProgressListener { step ->
        SharkLog.d { step.name }
      }

      val proguardMapping = proguardMappingFile?.let {
        ProguardMappingReader(it.inputStream()).readProguardMapping()
      }

      val heapAnalyzer = HeapAnalyzer(listener)
      SharkLog.d { "Analyzing heap dump $heapDumpFile" }
      val heapAnalysis = heapAnalyzer.analyze(
          heapDumpFile = heapDumpFile,
          leakingObjectFinder = FilteringLeakingObjectFinder(
              AndroidObjectInspectors.appLeakingObjectFilters
          ),
          referenceMatchers = AndroidReferenceMatchers.appDefaults,
          computeRetainedHeapSize = true,
          objectInspectors = AndroidObjectInspectors.appDefaults,
          proguardMapping = proguardMapping
      )

      SharkLog.d { heapAnalysis.toString() }
    }
  }
}