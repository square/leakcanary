package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import jline.console.ConsoleReader
import shark.DumpProcessCommand.Companion.dumpHeap
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.SharkCliCommand.Companion.sharkCliParams
import shark.SharkCliCommand.HeapDumpSource.HprofDirectorySource
import shark.SharkCliCommand.HeapDumpSource.HprofFileSource
import shark.SharkCliCommand.HeapDumpSource.ProcessSource

class HeapGrowthCommand : CliktCommand(
  name = "heap-growth",
  help = "Detect heap growth"
) {
  private val scenarioLoopsPerDump by option(
    "--loops", "-l",
    help = "The number of scenario iteration in between each heap dump."
  ).int().default(1).validate { if (it <= 0) fail("$it is not greater than 0") }

  private val maxHeapDumps by option(
    "--max", "-m",
    help = "The max number of heap dumps to perform live before returning the results."
  ).int().default(5).validate { if (it <= 1) fail("$it is not greater than 1") }

  override fun run() {
    val params = context.sharkCliParams

    val detector = RepeatedObjectGrowthDetectorAndroidFactory.create()

    val results = when (val source = params.source) {
      is HprofFileSource -> throw CliktError(
        "$commandName requires passing in a directory containing more than one hprof files."
      )

      is HprofDirectorySource -> {
        val hprofFiles = source.hprofFiles.sortedBy { it.name }
        if (hprofFiles.size == 1) {
          throw CliktError(
            "$commandName requires passing in a directory containing more than one hprof " +
              "files, could only find ${hprofFiles.first().name} in " +
              source.directory.absolutePath
          )
        }
        echo(
          "Detecting heap growth by going analyzing the following heap dumps in this " +
            "order:\n${hprofFiles.joinToString("\n") { it.name }}"
        )

        val heapGraphs = hprofFiles.asSequence().map { it.openHeapGraph() }

        detector.findRepeatedlyGrowingObjects(heapGraphs, scenarioLoopsPerDump)
      }

      is ProcessSource -> {
        echo("Detecting heap growth live")

        val liveDetector = HeapDumpingObjectGrowthDetector(
          maxHeapDumps = maxHeapDumps,
          heapGraphProvider = {
            dumpHeap(source.processName, source.deviceId).openHeapGraph()
          },
          scenarioLoopsPerDump = scenarioLoopsPerDump,
          detector = detector
        )

        val nTimes = if (scenarioLoopsPerDump > 1) "$scenarioLoopsPerDump times" else "once"

        liveDetector.findRepeatedlyGrowingObjects {
          ConsoleReader().readLine("Go through scenario $nTimes then press ENTER to dump heap")
        }
      }
    }
    echo("Results:\n" + results.joinToString("\n"))
  }
}
