package shark.dive.agent

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The tools that reach a device over `adb`: which devices there are, which processes of one can be dumped,
 * and taking a heap dump of one. [AgentTools] lists them last, after every tool that reads a heap dump.
 */
internal class AgentDeviceTools(
  private val heapDumps: AgentHeapDumps,
  /** The key a heap dump is open under, which [DUMP_HEAP] answers with. */
  private val keyOf: (AgentHeapDump) -> String
) {

  val all: List<AgentTool> = listOf(
    listDevices(),
    listProcesses(),
    dumpHeap()
  )

  private fun listDevices() = AgentTool(
    name = LIST_DEVICES,
    summary = "The Android devices adb is connected to.",
    description = "The Android devices `adb` is connected to, which is the first half of what the window's " +
      "`Take heap dump` button asks. $LIST_PROCESSES is the other half, and `adb` is the only thing here " +
      "that reaches outside this machine.",
    schema = schema()
  ) {
    val devices = heapDumps.devices()
    buildJsonObject {
      putJsonArray("devices") { AgentJson.devices(devices).forEach { add(it) } }
      if (devices.isEmpty()) {
        put(
          "problem",
          "`adb` is connected to no device. Plug one in, start an emulator, or ask whoever is at the " +
            "machine to."
        )
      }
    }
  }

  private fun listProcesses() = AgentTool(
    name = LIST_PROCESSES,
    summary = "The app processes of one device, which are what can be dumped.",
    description = "The app processes running on one device, each with its pid and whether it is one of the " +
      "system's own. What can be dumped is an app built debuggable, or every process of a device whose " +
      "whole build is. That second case is `dumpsAnyProcess` in $LIST_DEVICES: a `userdebug` emulator " +
      "image has it, a phone does not, and it is the only way a system app here is dumpable.",
    schema = schema(
      DEVICE to string("The serial number of the device, from $LIST_DEVICES.")
    )
  ) { arguments ->
    val serialNumber = arguments.string(DEVICE)
    val processes = heapDumps.processesOf(serialNumber)
    buildJsonObject {
      put("device", serialNumber)
      putJsonArray("processes") { AgentJson.processes(processes).forEach { add(it) } }
    }
  }

  private fun dumpHeap() = AgentTool(
    name = DUMP_HEAP,
    summary = "Takes a heap dump off a device and opens it. Minutes, on a large app.",
    description = "Takes a heap dump of a running process, opens it in a window of Shark Dive and " +
      "answers once it can be read, which is what the window's `Take heap dump` button does. Minutes, " +
      "on a large app: the device writes the dump, it is pulled over `adb`, and then opened. The garbage " +
      "is collected first either way, so what is in the dump is what is really still held. That is " +
      "`am dumpheap -g` from API 27, and below that the same collection run in the process over JDWP. " +
      "$LIST_DEVICES is the devices and $LIST_PROCESSES is one device's processes.",
    schema = schema(
      DEVICE to string("The serial number of the device, from $LIST_DEVICES."),
      PROCESS to string("The name of the process to dump, from $LIST_PROCESSES.")
    )
  ) { arguments ->
    val dump = heapDumps.dumpHeap(
      serialNumber = arguments.string(DEVICE),
      processName = arguments.string(PROCESS)
    )
    buildJsonObject {
      put(HEAP_DUMP_KEY, keyOf(dump))
      put("heapDumpPath", dump.heapDumpPath)
      put("dumped", true)
      put("next", NEXT_WITH_A_NEW_DUMP)
    }
  }
}

internal const val LIST_DEVICES = "list_devices"
internal const val LIST_PROCESSES = "list_processes"
internal const val DUMP_HEAP = "dump_heap"

private const val DEVICE = "device"
private const val PROCESS = "process"
