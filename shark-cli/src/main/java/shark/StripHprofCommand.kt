package shark

import com.github.ajalt.clikt.core.CliktCommand
import shark.SharkCliCommand.Companion.retrieveHeapDumpFile
import shark.SharkCliCommand.Companion.sharkCliParams

class StripHprofCommand : CliktCommand(
    name = "strip-hprof",
    help = "Replace all primitive arrays from the provided heap dump with arrays of zeroes and generate a new \"-stripped.hprof\" file."
) {

  override fun run() {
    val heapDumpFile = retrieveHeapDumpFile(context.sharkCliParams)
    SharkLog.d { "Stripping primitive arrays in heap dump $heapDumpFile" }
    val stripper = HprofPrimitiveArrayStripper()
    val outputFile = stripper.stripPrimitiveArrays(heapDumpFile)
    echo("Created hprof with stripped primitive arrays to $outputFile")
  }
}