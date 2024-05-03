package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import jline.console.ConsoleReader
import jline.console.UserInterruptException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
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
        ReferencePattern.instanceField(className, fieldName).ignored()
      }
    } ?: emptyList()

    val ignoredStaticFieldReferences = ignoredStaticFields?.let { ignoredStaticFields ->
      ignoredStaticFields.map { ignoredStaticField ->
        val className = ignoredStaticField.substringBeforeLast(".")
        val fieldName = ignoredStaticField.substringAfterLast(".")
        ReferencePattern.staticField(className, fieldName).ignored()
      }
    } ?: emptyList()

    val referenceMatchers = AndroidObjectGrowthReferenceMatchers.defaults +
      ignoredInstanceFieldReferences +
      ignoredStaticFieldReferences

    val androidDetector = ObjectGrowthDetector
      .forAndroidHeap(referenceMatchers)

    data class Metrics(
      val randomAccessByteReads: Long,
      val randomAccessReadCount: Long,
      val duration: Duration
    )

    val metrics = mutableListOf<Metrics>()

    when (val source = params.source) {
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
        val detector = androidDetector
          .repeatingHeapGraph()
        val results = detector.findRepeatedlyGrowingObjects(
          heapGraphSequence = heapGraphs,
          initialState = InitialState(scenarioLoopsPerDump, hprofFiles.size),
        ).also {
          previous?.let {
            val finishTime = System.nanoTime().nanoseconds
            metrics += Metrics(
              it.randomAccessByteReads, it.randomAccessReadCount, finishTime - previousStartTime
            )
          }
        }
        echo("Results: $results")
        echo("Found ${results.growingObjects.size} growing objects")
      }

      is ProcessSource -> {
        echo("Detecting heap growth live")

        ConsoleReader().readLine("Press ENTER to dump heap when ready to start")

        val firstTraversal = androidDetector.findGrowingObjects(
          heapGraph = source.dumpHeapAndOpenGraph(),
          previousTraversal = InitialState(scenarioLoopsPerDump)
        )

        val nTimes = if (scenarioLoopsPerDump > 1) "$scenarioLoopsPerDump times" else "once"

        ConsoleReader().readLine("Go through scenario $nTimes then press ENTER to dump heap")
        var latestTraversal = androidDetector.findGrowingObjects(
          heapGraph = source.dumpHeapAndOpenGraph(),
          previousTraversal = firstTraversal
        ) as HeapGrowthTraversal

        while (true) {

          echo("Results: $latestTraversal")
          echo(
            "Found ${latestTraversal.growingObjects.size} objects growing over the last ${latestTraversal.traversalCount} heap dumps."
          )

          val consoleReader = ConsoleReader()

          var reset = false

          var promptForCommand = true
          while (promptForCommand) {
            if (latestTraversal.isGrowing) {
              echo("To keep going, go through scenario $nTimes.")
              echo("Then, either press ENTER or enter 'r' to reset and use the last heap dump as the new baseline.")
              echo("To quit, enter 'q'.")
              val command = consoleReader.readCommand(

              )
              when (command) {
                "q" -> throw PrintMessage("Quitting.")
                "r" -> {
                  reset = true
                  promptForCommand = false
                }

                "" -> promptForCommand = false
                else -> {
                  echo("Invalid command '$command'")
                }
              }
            } else {
              echo("As the last heap dump found 0 growing objects, there's no point continuing with the same heap dump baseline.")
              echo("To keep going, go through scenario $nTimes then press ENTER to use the last heap dump as the NEW baseline.")
              echo("To quit, enter 'q'.")
              val command = consoleReader.readCommand()
              when (command) {
                "q" -> throw PrintMessage("Quitting.")
                "" -> {
                  promptForCommand = false
                  reset = true
                }

                else -> {
                  echo("Invalid command '$command'")
                }
              }
            }
          }
          val nextInputTraversal = if (reset) {
            FirstHeapTraversal(
              shortestPathTree = latestTraversal.shortestPathTree.copyResettingAsInitialTree(),
              previousTraversal = InitialState(latestTraversal.scenarioLoopsPerGraph, null)
            )
          } else {
            latestTraversal
          }
          latestTraversal = androidDetector.findGrowingObjects(
            heapGraph = source.dumpHeapAndOpenGraph(),
            previousTraversal = nextInputTraversal
          ) as HeapGrowthTraversal
        }
      }
    }

    SharkLog.d { "Metrics:\n${metrics.joinToString("\n")}" }
  }

  private fun ConsoleReader.readCommand(): String? {
    val input = try {
      readLine()
    } catch (ignored: UserInterruptException) {
      throw PrintMessage("Program interrupted by user")
    }
    return input
  }

  private fun ProcessSource.dumpHeapAndOpenGraph(): CloseableHeapGraph =
    dumpHeap(processName, deviceId).openHeapGraph()
}
