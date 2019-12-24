package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import shark.SharkCli.Companion.SHARK_CLI_COMMAND
import shark.SharkCli.Companion.USAGE_HELP_TAG
import shark.SharkCli.Companion.runCommand
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DumpProcess : CliktCommand(
    name = COMMAND,
    help = "Dumps the heap for the provided partial $PROCESS_NAME_ARG_NAME and pulls the hprof file.",
    helpTags = mapOf(
        USAGE_HELP_TAG to "$SHARK_CLI_COMMAND $COMMAND [$DEVICE_USAGE] $PROCESS_NAME_ARG_NAME"
    ),
    printHelpOnEmptyArgs = true
) {

  private val processName by argument(name = PROCESS_NAME_ARG_NAME, help = PROCESS_NAME_HELP)

  private val device by option(
      *DEVICE_OPTION_NAMES, metavar = DEVICE_METAVAR, help = DEVICE_OPTION_HELP
  )

  override fun run() {
    dumpHeap(processName, device)
  }

  companion object {

    private const val COMMAND = "dump-process"

    const val PROCESS_NAME_HELP =
      "Full or partial name of a process, e.g. \"example\" would match \"com.example.app\""
    const val PROCESS_NAME_ARG_NAME = "PROCESS_NAME"

    val DEVICE_OPTION_NAMES = arrayOf("-d", "--device")
    const val DEVICE_OPTION_HELP = "device/emulator id"
    const val DEVICE_METAVAR = "ID"
    const val DEVICE_USAGE = "--device ID"

    private val SPACE_PATTERN = Regex("\\s+")

    @Suppress("ThrowsCount")
    fun dumpHeap(
      processNameParam: String,
      maybeDeviceId: String?
    ): File {
      val workingDirectory = File(System.getProperty("user.dir"))

      val deviceList = runCommand(workingDirectory, "adb", "devices")

      val connectedDevices = deviceList.lines()
          .drop(1)
          .filter { it.isNotBlank() }
          .map { SPACE_PATTERN.split(it)[0] }

      val deviceId = if (connectedDevices.isEmpty()) {
        throw PrintMessage("Error: No device connected to adb")
      } else if (maybeDeviceId == null) {
        if (connectedDevices.size == 1) {
          connectedDevices[0]
        } else {
          throw PrintMessage(
              "Error: more than one device/emulator connected to adb," +
                  " use '--device ID' argument with one of $connectedDevices"
          )
        }
      } else {
        if (maybeDeviceId in connectedDevices) {
          maybeDeviceId
        } else {
          throw PrintMessage(
              "Error: device '$maybeDeviceId' not in the list of connected devices $connectedDevices"
          )
        }
      }

      val processList = runCommand(workingDirectory, "adb", "-s", deviceId, "shell", "ps")

      val matchingProcesses = processList.lines()
          .filter { it.contains(processNameParam) }
          .map {
            val columns = SPACE_PATTERN.split(it)
            columns[8] to columns[1]
          }

      val (processName, processId) = when {
        matchingProcesses.size == 1 -> {
          matchingProcesses[0]
        }
        matchingProcesses.isEmpty() -> {
          throw PrintMessage("Error: No process matching \"$processNameParam\"")
        }
        else -> {
          matchingProcesses.firstOrNull { it.first == processNameParam }
              ?: throw PrintMessage(
                  "Error: More than one process matches \"$processNameParam\" but none matches exactly: ${matchingProcesses.map { it.first }}"
              )
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

      val pullResult =
        runCommand(workingDirectory, "adb", "-s", deviceId, "pull", heapDumpDevicePath)
      SharkLog.d { pullResult }
      SharkLog.d { "Removing $heapDumpDevicePath" }

      runCommand(workingDirectory, "adb", "-s", deviceId, "shell", "rm", heapDumpDevicePath)

      val heapDumpFile = File(workingDirectory, heapDumpFileName)
      SharkLog.d { "Pulled heap dump to $heapDumpFile" }

      return heapDumpFile
    }

  }
}