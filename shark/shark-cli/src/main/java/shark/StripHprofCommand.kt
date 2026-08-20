package shark

import com.github.ajalt.clikt.core.CliktCommand
import shark.SharkCliCommand.Companion.retrieveHeapDumpFile
import shark.SharkCliCommand.Companion.sharkCliParams

class StripHprofCommand : CliktCommand(
  name = "strip-hprof",
  help = """
    |Replace all primitive arrays from the provided heap dump with arrays of zeroes and generate a new "-stripped.hprof" file.
    |
    |Primitive wrapper instances are updated to wrap 0 as well. Everything else is copied over
    |unchanged, which includes the string records holding the class, field and method names the rest
    |of the heap dump refers to. Those hold no runtime data in a heap dump from Android, but a heap
    |dump from a JVM also holds every string constant of every loaded class in them, so stripping a
    |JVM heap dump leaves the constants written in the code behind.
    |
    |A gzipped heap dump is read gzipped and written back gzipped, so "app.hprof.gz" gives you
    |"app-stripped.hprof.gz".
    """.trimMargin()
) {

  override fun run() {
    val heapDumpFile = retrieveHeapDumpFile(context.sharkCliParams)
    SharkLog.d { "Stripping primitive arrays in heap dump $heapDumpFile" }
    val stripper = HprofPrimitiveArrayStripper()
    val outputFile = stripper.stripPrimitiveArrays(heapDumpFile)
    echo("Created hprof with stripped primitive arrays to $outputFile")
  }
}