package leakcanary

import android.os.Build
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import shark.SharkLog

class UiAutomatorShellHeapDumper(private val withGc: Boolean) : HeapDumper {
  override fun dumpHeap(heapDumpFile: File) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val packageName = instrumentation.targetContext.packageName

    val device = UiDevice.getInstance(instrumentation)

    val processList = device.executeShellCommand("ps")
    val matchingProcesses = processList.lines()
      .filter { it.contains(packageName) }
      .map {
        val columns = SPACE_PATTERN.split(it)
        columns[8] to columns[1]
      }

    val (processName, processId) = when {
      matchingProcesses.size == 1 -> {
        matchingProcesses[0]
      }

      matchingProcesses.isEmpty() -> {
        error("Error: No process named \"$packageName\"")
      }

      else -> {
        matchingProcesses.firstOrNull { it.first == packageName }
          ?: error(
            "Error: More than one process matches \"$packageName\" but none matches exactly: ${matchingProcesses.map { it.first }}"
          )
      }
    }

    SharkLog.d { "Dumping heap for process \"$processName\" with pid $processId to ${heapDumpFile.absolutePath}" }

    val forceGc = if (withGc && Build.VERSION.SDK_INT >= 27) {
      "-g "
    } else {
      ""
    }

    device.executeShellCommand("am dumpheap $forceGc$processId ${heapDumpFile.absolutePath}")
    // Make the heap dump world readable, otherwise we can't read it.
    device.executeShellCommand("chmod +r ${heapDumpFile.absolutePath}")

    // TODO Delete the heap dump. Have delete() be customizable on the dumper+deleter
  }

  private companion object {
    private val SPACE_PATTERN = Regex("\\s+")
  }

}

fun HeapDumper.Companion.forUiAutomatorAsShell(withGc: Boolean) = UiAutomatorShellHeapDumper(withGc)
