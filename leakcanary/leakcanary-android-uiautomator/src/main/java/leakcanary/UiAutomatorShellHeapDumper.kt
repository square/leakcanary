package leakcanary

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import shark.SharkLog

class UiAutomatorShellHeapDumper(
  private val withGc: Boolean,
  private val dumpedAppPackageName: String
) : HeapDumper {
  override fun dumpHeap(heapDumpFile: File) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    val processId = device.getPidsForProcess(dumpedAppPackageName)
      // TODO Figure out what to do when we get more than one.
      .single()

    SharkLog.d { "Dumping heap for \"$dumpedAppPackageName\" with pid $processId to ${heapDumpFile.absolutePath}" }

    val forceGc = if (withGc && Build.VERSION.SDK_INT >= 27) {
      "-g "
    } else {
      ""
    }

    device.executeShellCommand("am dumpheap $forceGc$processId ${heapDumpFile.absolutePath}")
    // Make the heap dump world readable, otherwise we can't read it.
    device.executeShellCommand("chmod +r ${heapDumpFile.absolutePath}")
  }

  // Based on https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:benchmark/benchmark-common/src/main/java/androidx/benchmark/Shell.kt;l=467;drc=8f2ba6a5469f67b7e385878d704f97bde22419ce
  private fun UiDevice.getPidsForProcess(processName: String): List<Int> {
    if (Build.VERSION.SDK_INT >= 23) {
      return pgrepLF(pattern = processName)
        .mapNotNull { (pid, fullProcessName) ->
          if (fullProcessNameMatchesProcess(fullProcessName, processName)) {
            pid
          } else {
            null
          }
        }
    }
    val processList = executeShellCommand("ps")
    return processList.lines()
      .filter { psLineContainsProcess(it, processName) }
      .map {
        val columns = SPACE_PATTERN.split(it)
        columns[1].toInt()
      }
  }

  private fun UiDevice.pgrepLF(pattern: String): List<Pair<Int, String>> {
    return executeShellCommand("pgrep -l -f $pattern")
      .split(Regex("\r?\n"))
      .filter { it.isNotEmpty() }
      .map {
        val (pidString, process) = it.trim().split(" ")
        Pair(pidString.toInt(), process)
      }
  }

  private fun psLineContainsProcess(
    psOutputLine: String,
    processName: String
  ): Boolean {
    return psOutputLine.endsWith(" $processName") || psOutputLine.endsWith("/$processName")
  }

  private fun fullProcessNameMatchesProcess(
    fullProcessName: String,
    processName: String
  ): Boolean {
    return fullProcessName == processName || fullProcessName.endsWith("/$processName")
  }

  private companion object {
    private val SPACE_PATTERN = Regex("\\s+")
  }
}

fun HeapDumper.Companion.forUiAutomatorAsShell(
  withGc: Boolean,
  dumpedAppPackageName: String = InstrumentationRegistry.getInstrumentation().targetContext.packageName
) = UiAutomatorShellHeapDumper(withGc, dumpedAppPackageName)
