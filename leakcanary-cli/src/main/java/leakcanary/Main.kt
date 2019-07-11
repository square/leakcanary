package leakcanary

import leakcanary.AnalyzerProgressListener.Step
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit.SECONDS

fun main(args: Array<String>) {
  CanaryLog.logger = CLILogger()
  when {
    args.size == 2 && args[0] == "analyze-process" -> {
      val heapDumpFile = dumpHeap(args[1])
      analyze(heapDumpFile)
    }
    args.size == 2 && args[0] == "dump-process" -> dumpHeap(args[1])
    args.size == 2 && args[0] == "analyze-file" -> analyze(File(args[1]))
    else -> printHelp()
  }
}

fun printHelp() {
  val workingDirectory = File(System.getProperty("user.dir"))

  CanaryLog.d("""
    LeakCanary CLI
    Running in directory $workingDirectory

    Commands: [analyze-process, dump-process, analyze-file]

    analyze-process: Dumps the heap for the provided process name, pulls the hprof file and analyzes it.
      USAGE: analyze-process PROCESS_PACKAGE_NAME

    dump-process: Dumps the heap for the provided process name and pulls the hprof file.
      USAGE: dump-process PROCESS_PACKAGE_NAME

    analyze-file: Analyzes the provided hprof file.
      USAGE: analyze-file HPROF_FILE_PATH
  """.trimIndent())
}

private fun dumpHeap(packageName: String): File {
  val workingDirectory = File(System.getProperty("user.dir"))

  val processList = runCommand(workingDirectory, "adb", "shell", "ps")

  val matchingProcesses = processList.lines()
      .filter { it.contains(packageName) }
      .map {
        val columns = Regex("\\s+").split(it)
        columns[8] to columns[1]
      }

  val (processName, processId) = if (matchingProcesses.size == 1) {
    matchingProcesses[0]
  } else if (matchingProcesses.isEmpty()) {
    CanaryLog.d("No process matching \"$packageName\"")
    System.exit(1)
    throw RuntimeException("System exiting with error")
  } else {
    val matchingExactly = matchingProcesses.firstOrNull { it.first == packageName }
    if (matchingExactly != null) {
      matchingExactly
    } else {
      CanaryLog.d(
          "More than one process matches \"$packageName\" but none matches exactly: ${matchingProcesses.map { it.first }}"
      )
      System.exit(1)
      throw RuntimeException("System exiting with error")
    }
  }

  val heapDumpFileName =
    SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'-$processName.hprof'", Locale.US).format(
        Date()
    )

  val heapDumpDevicePath = "/data/local/tmp/$heapDumpFileName"

  CanaryLog.d(
      "Dumping heap for process \"$processName\" with pid $processId to $heapDumpDevicePath"
  )

  runCommand(workingDirectory, "adb", "shell", "am", "dumpheap", processId, heapDumpDevicePath)

  // Dump heap takes time but adb returns immediately.
  Thread.sleep(5000)

  CanaryLog.d("Pulling $heapDumpDevicePath")

  val pullResult = runCommand(workingDirectory, "adb", "pull", heapDumpDevicePath)
  CanaryLog.d(pullResult)
  CanaryLog.d("Removing $heapDumpDevicePath")

  runCommand(workingDirectory, "adb", "shell", "rm", heapDumpDevicePath)

  val heapDumpFile = File(workingDirectory, heapDumpFileName)
  CanaryLog.d("Pulled heap dump to $heapDumpFile")

  return heapDumpFile
}

private fun runCommand(
  directory: File,
  vararg arguments: String
): String {
  val process = ProcessBuilder(*arguments)
      .directory(directory)
      .start()
      .also { it.waitFor(10, SECONDS) }

  if (process.exitValue() != 0) {
    throw Exception(process.errorStream.bufferedReader().readText())
  }
  return process.inputStream.bufferedReader()
      .readText()
}

private fun analyze(heapDumpFile: File) {
  val listener = object : AnalyzerProgressListener {
    override fun onProgressUpdate(step: Step) {
      CanaryLog.d(step.name)
    }
  }

  val heapAnalyzer = HeapAnalyzer(listener)
  CanaryLog.d("Analyzing heap dump $heapDumpFile")
  val heapAnalysis = heapAnalyzer.checkForLeaks(
      heapDumpFile, AndroidKnownReference.mapToExclusions(AndroidKnownReference.appDefaults), true,
      AndroidObjectInspectors.defaultInspectors()
  )

  CanaryLog.d(heapAnalysis.toString())
}
