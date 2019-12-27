package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import shark.SharkCliCommand.Companion.echo
import shark.SharkCliCommand.Companion.retrieveHeapDumpFile
import shark.SharkCliCommand.Companion.runCommand
import shark.SharkCliCommand.Companion.sharkCliParams
import shark.SharkCliCommand.HeapDumpSource.ProcessSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DumpProcessCommand : CliktCommand(
    name = "dump-process",
    help = "Dump the heap and pull the hprof file."
) {

  override fun run() {
    val params = context.sharkCliParams
    if (params.source !is ProcessSource) {
      throw UsageError("dump-process must be used with --process")
    }
    val file = retrieveHeapDumpFile(params)
    echo("Pulled heap dump to $file")
  }

  companion object {

    private val SPACE_PATTERN = Regex("\\s+")

    @Suppress("ThrowsCount")
    fun CliktCommand.dumpHeap(
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

      echo(
        "Dumping heap on $deviceId for process \"$processName\" with pid $processId to $heapDumpDevicePath"
      )

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