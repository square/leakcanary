package shark

import com.github.ajalt.clikt.core.CliktCommand
import shark.SharkCliCommand.Companion.echo
import shark.SharkCliCommand.Companion.retrieveHeapDumpFile
import shark.SharkCliCommand.Companion.sharkCliParams
import java.io.File

class AnalyzeCommand : CliktCommand(
    name = "analyze",
    help = "Analyze a heap dump."
) {

  override fun run() {
    val params = context.sharkCliParams
    analyze(retrieveHeapDumpFile(params), params.obfuscationMappingPath)
  }

  companion object {
    fun CliktCommand.analyze(
      heapDumpFile: File,
      proguardMappingFile: File?
    ) {
      val proguardMapping = proguardMappingFile?.let {
        ProguardMappingReader(it.inputStream()).readProguardMapping()
      }
      val objectInspectors = AndroidObjectInspectors.appDefaults.toMutableList()

      val listener = OnAnalysisProgressListener { step ->
        SharkLog.d { "Analysis in progress, working on: ${step.name}" }
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
          objectInspectors = objectInspectors,
          proguardMapping = proguardMapping
      )
      echo(heapAnalysis)
    }
  }
}