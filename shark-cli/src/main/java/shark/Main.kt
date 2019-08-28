package shark

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit.SECONDS

fun main(args: Array<String>) {
  SharkLog.logger = CLILogger()
  when (args.size) {
    2 -> {
      when (args[0]) {
        "analyze-process" -> {
          val heapDumpFile = dumpHeap(args[1])
          analyze(heapDumpFile)
        }
        "dump-process" -> dumpHeap(args[1])
        "analyze-hprof" -> analyze(File(args[1]))
        "strip-hprof" -> stripHprof(File(args[1]))
        else -> printHelp()
      }
    }
    4 -> {
      val heapFile = when (args[0]) {
        "analyze-process" -> {
          dumpHeap(args[1])
        }
        "analyze-hprof" -> {
          File(args[1])
        }
        else -> {
          printHelp()
          null
        }
      }

      val mappingFile = if (args[2] == "-proguard-mapping") File(args[3]) else null

      if (heapFile != null && mappingFile != null) {
        analyze(heapFile, mappingFile)
      } else {
        printHelp()
      }
    }
    else -> printHelp()
  }
}

fun printHelp() {
  val workingDirectory = File(System.getProperty("user.dir"))

  // ASCII art is a remix of a shark from -David "TAZ" Baltazar- and chick from jgs.
  SharkLog.d {
    """
    Shark CLI, running in directory $workingDirectory

                     ^`.                 .=""=.
     ^_              \  \               / _  _ \
     \ \             {   \             |  d  b  |
     {  \           /     `~~~--__     \   /\   /
     {   \___----~~'              `~~-_/'-=\/=-'\,
      \                         /// a  `~.      \ \
      / /~~~~-, ,__.    ,      ///  __,,,,)      \ |
      \/      \/    `~~~;   ,---~~-_`/ \        / \/
                       /   /            '.    .'
                      '._.'             _|`~~`|_
                                        /|\  /|\

    Commands: [analyze-process, dump-process, analyze-hprof, strip-hprof]

    analyze-process: Dumps the heap for the provided process name, pulls the hprof file and analyzes it.
      USAGE: analyze-process PROCESS_PACKAGE_NAME
             (optional) -proguard-mapping PROGUARD_MAPPING_FILE_PATH

    dump-process: Dumps the heap for the provided process name and pulls the hprof file.
      USAGE: dump-process PROCESS_PACKAGE_NAME

    analyze-hprof: Analyzes the provided hprof file.
      USAGE: analyze-hprof HPROF_FILE_PATH
             (optional) -proguard-mapping PROGUARD_MAPPING_FILE_PATH

    strip-hprof: Replaces all primitive arrays from the provided hprof file with arrays of zeroes and generates a new "-stripped" hprof file.
      USAGE: strip-hprof HPROF_FILE_PATH
  """.trimIndent()
  }
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
    SharkLog.d { "No process matching \"$packageName\"" }
    System.exit(1)
    throw RuntimeException("System exiting with error")
  } else {
    val matchingExactly = matchingProcesses.firstOrNull { it.first == packageName }
    if (matchingExactly != null) {
      matchingExactly
    } else {
      SharkLog.d {
        "More than one process matches \"$packageName\" but none matches exactly: ${matchingProcesses.map { it.first }}"
      }
      System.exit(1)
      throw RuntimeException("System exiting with error")
    }
  }

  val heapDumpFileName =
    SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'-$processName.hprof'", Locale.US).format(
        Date()
    )

  val heapDumpDevicePath = "/data/local/tmp/$heapDumpFileName"

  SharkLog.d {
    "Dumping heap for process \"$processName\" with pid $processId to $heapDumpDevicePath"
  }

  runCommand(
      workingDirectory, "adb", "shell", "am", "dumpheap", processId, heapDumpDevicePath
  )

  // Dump heap takes time but adb returns immediately.
  Thread.sleep(5000)

  SharkLog.d { "Pulling $heapDumpDevicePath" }

  val pullResult = runCommand(workingDirectory, "adb", "pull", heapDumpDevicePath)
  SharkLog.d { pullResult }
  SharkLog.d { "Removing $heapDumpDevicePath" }

  runCommand(workingDirectory, "adb", "shell", "rm", heapDumpDevicePath)

  val heapDumpFile = File(workingDirectory, heapDumpFileName)
  SharkLog.d { "Pulled heap dump to $heapDumpFile" }

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

private fun analyze(
  heapDumpFile: File,
  proguardMappingFile: File? = null
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
      heapDumpFile, AndroidReferenceMatchers.appDefaults, true,
      AndroidObjectInspectors.appDefaults,
      proguardMapping = proguardMapping
  )

  SharkLog.d { heapAnalysis.toString() }
}

private fun stripHprof(heapDumpFile: File) {
  SharkLog.d { "Stripping primitive arrays in heap dump $heapDumpFile" }
  val stripper = HprofPrimitiveArrayStripper()
  val outputFile = stripper.stripPrimitiveArrays(heapDumpFile)
  SharkLog.d { "Stripped primitive arrays to $outputFile" }
}