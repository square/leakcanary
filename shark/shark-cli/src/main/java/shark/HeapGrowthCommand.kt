package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import jline.console.ConsoleReader
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import shark.DumpProcessCommand.Companion.dumpHeap
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ReferencePattern.InstanceFieldPattern
import shark.ReferencePattern.StaticFieldPattern
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

  private val ignoredFields by option("--ignored-fields")
    .split(",")

  private val ignoredStaticFields by option("--ignored-static-fields")
    .split(",")

  override fun run() {
    val params = context.sharkCliParams

    val ignoredInstanceFieldReferences = ignoredFields?.let { ignoredFields ->
      ignoredFields.map { ignoredField ->
        val className = ignoredField.substringBeforeLast(".")
        val fieldName = ignoredField.substringAfterLast(".")
        IgnoredReferenceMatcher(InstanceFieldPattern(className, fieldName))
      }
    } ?: emptyList()

    val ignoredStaticFieldReferences = ignoredStaticFields?.let { ignoredStaticFields ->
      ignoredStaticFields.map { ignoredStaticField ->
        val className = ignoredStaticField.substringBeforeLast(".")
        val fieldName = ignoredStaticField.substringAfterLast(".")
        IgnoredReferenceMatcher(StaticFieldPattern(className, fieldName))
      }
    } ?: emptyList()

    val referenceMatchers = AndroidHeapGrowthIgnoredReferences.defaults +
      ignoredInstanceFieldReferences +
      ignoredStaticFieldReferences

    val detector = RepeatedObjectGrowthDetectorAndroidFactory.create(referenceMatchers)

    data class Metrics(
      val randomAccessByteReads: Long,
      val randomAccessReadCount: Long,
      val duration: Duration
    )

    val metrics = mutableListOf<Metrics>()

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

        var previous: ConstantMemoryMetricsDualSourceProvider? = null
        var previousStartTime = 0L.nanoseconds

        val heapGraphs = hprofFiles.asSequence().map { file ->
          val openTime = System.nanoTime().nanoseconds
          previous?.let {
            metrics += Metrics(
              it.randomAccessByteReads, it.randomAccessReadCount, openTime - previousStartTime
            )
          }
          val sourceProvider = ConstantMemoryMetricsDualSourceProvider(FileSourceProvider(file))
          previous = sourceProvider
          previousStartTime = openTime
          sourceProvider.openHeapGraph()
        }
        detector.findRepeatedlyGrowingObjects(heapGraphs, scenarioLoopsPerDump).also {
          previous?.let {
            val finishTime = System.nanoTime().nanoseconds
            metrics += Metrics(
              it.randomAccessByteReads, it.randomAccessReadCount, finishTime - previousStartTime
            )
          }
        }
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
    echo("Found ${results.size} growing objects")
    SharkLog.d { "Metrics:\n${metrics.joinToString("\n")}" }
  }
}
