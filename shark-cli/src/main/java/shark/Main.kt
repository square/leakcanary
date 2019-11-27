package shark

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  SharkLog.logger = CLILogger()
  if (args.isEmpty()) {
    printHelp()
    return
  }

  var argIndex = -1

  when (val command = args[++argIndex]) {
    "dump-process" -> {
      val packageName = args[++argIndex]
      argIndex++
      val remainderArgs = args.drop(argIndex)
      val deviceId = readDeviceIdFromArgs(remainderArgs)
      dumpHeap(packageName, deviceId)
    }
    "analyze-process" -> {
      val packageName = args[++argIndex]
      argIndex++
      val remainderArgs = args.drop(argIndex)
      val deviceId = readDeviceIdFromArgs(remainderArgs)
      val heapDumpFile = dumpHeap(packageName, deviceId)
      val mappingFile = readMappingFileFromArgs(remainderArgs)
      analyze(heapDumpFile, mappingFile)
    }
    "analyze-hprof" -> {
      val hprofPath = args[++argIndex]
      argIndex++
      val remainderArgs = args.asList()
          .subList(argIndex, args.size)
      val mappingFile = readMappingFileFromArgs(remainderArgs)
      analyze(File(hprofPath), mappingFile)
    }
    "strip-hprof" -> {
      val hprofPath = args[++argIndex]
      stripHprof(File(hprofPath))
    }
    else -> {
      SharkLog.d {
        "Error: unknown command [$command]"
      }
      printHelp()
    }
  }
}

private fun readMappingFileFromArgs(args: List<String>): File? {
  val tagIndex = args.indexOfFirst {
    it == "-p" || it == "--proguard-mapping"
  }
  if (tagIndex == -1 || tagIndex == args.lastIndex) {
    return null
  }
  return File(args[tagIndex + 1])
}

private fun readDeviceIdFromArgs(args: List<String>): String? {
  val tagIndex = args.indexOfFirst {
    it == "-d" || it == "--device"
  }
  if (tagIndex == -1 || tagIndex == args.lastIndex) {
    return null
  }
  return args[tagIndex + 1]
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
               [-d ID, --device ID]                optional device/emulator id
               [-p PATH, --proguard-mapping PATH]  optional path to Proguard mapping file

    dump-process: Dumps the heap for the provided process name and pulls the hprof file.
      USAGE: dump-process PROCESS_PACKAGE_NAME
               [-d ID, --device ID]  optional device/emulator id

    analyze-hprof: Analyzes the provided hprof file.
      USAGE: analyze-hprof HPROF_FILE_PATH
               [-p PATH, --proguard-mapping PATH]  optional path to Proguard mapping file

    strip-hprof: Replaces all primitive arrays from the provided hprof file with arrays of zeroes and generates a new "-stripped" hprof file.
      USAGE: strip-hprof HPROF_FILE_PATH
  """.trimIndent()
  }
}

private fun dumpHeap(
  packageName: String,
  maybeDeviceId: String?
): File {
  val workingDirectory = File(System.getProperty("user.dir"))

  val deviceList = runCommand(workingDirectory, "adb", "devices")

  val connectedDevices = deviceList.lines()
      .drop(1)
      .filter { it.isNotBlank() }
      .map { SPACE_PATTERN.split(it)[0] }

  val deviceId = if (connectedDevices.isEmpty()) {
    SharkLog.d { "Error: No device connected to adb" }
    exitProcess(1)
  } else if (maybeDeviceId == null) {
    if (connectedDevices.size == 1) {
      connectedDevices[0]
    } else {
      SharkLog.d {
        "Error: more than one device/emulator connected to adb," +
            " use '--device ID' argument with one of $connectedDevices"
      }
      exitProcess(1)
    }
  } else {
    if (maybeDeviceId in connectedDevices) {
      maybeDeviceId
    } else {
      SharkLog.d { "Error: device '$maybeDeviceId' not in the list of connected devices $connectedDevices" }
      exitProcess(1)
    }
  }

  val processList = runCommand(workingDirectory, "adb", "-s", deviceId, "shell", "ps")

  val matchingProcesses = processList.lines()
      .filter { it.contains(packageName) }
      .map {
        val columns = SPACE_PATTERN.split(it)
        columns[8] to columns[1]
      }

  val (processName, processId) = if (matchingProcesses.size == 1) {
    matchingProcesses[0]
  } else if (matchingProcesses.isEmpty()) {
    SharkLog.d { "Error: No process matching \"$packageName\"" }
    exitProcess(1)
  } else {
    val matchingExactly = matchingProcesses.firstOrNull { it.first == packageName }
    if (matchingExactly != null) {
      matchingExactly
    } else {
      SharkLog.d {
        "Error: More than one process matches \"$packageName\" but none matches exactly: ${matchingProcesses.map { it.first }}"
      }
      exitProcess(1)
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
      workingDirectory, "adb", "-s", deviceId, "shell", "am", "dumpheap", processId,
      heapDumpDevicePath
  )

  // Dump heap takes time but adb returns immediately.
  Thread.sleep(5000)

  SharkLog.d { "Pulling $heapDumpDevicePath" }

  val pullResult = runCommand(workingDirectory, "adb",  "-s", deviceId, "pull", heapDumpDevicePath)
  SharkLog.d { pullResult }
  SharkLog.d { "Removing $heapDumpDevicePath" }

  runCommand(workingDirectory, "adb", "-s", deviceId, "shell", "rm", heapDumpDevicePath)

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
    throw Exception(
        "Failed command: '${arguments.joinToString(
            " "
        )}', error output: '${process.errorStream.bufferedReader().readText()}'"
    )
  }
  return process.inputStream.bufferedReader()
      .readText()
}

private fun analyze(
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

private val SPACE_PATTERN = Regex("\\s+")