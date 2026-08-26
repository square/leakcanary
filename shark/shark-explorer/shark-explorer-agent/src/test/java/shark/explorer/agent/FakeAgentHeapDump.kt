package shark.explorer.agent

import java.io.Closeable
import java.io.File
import shark.SharkLog
import shark.explorer.AndroidDevice
import shark.explorer.DeviceProcess
import shark.explorer.HeapExplorer
import shark.explorer.LeakStatusOverride
import shark.explorer.LeakStatusOverrides
import shark.explorer.Place

/**
 * A heap dump open the way a window has one, without a window.
 *
 * Which is the whole reason [AgentHeapDump] is an interface: every tool is a read of a heap dump and a write
 * of a verdict or a note, so a test of what a tool answers needs a dump and three fields, and none of
 * Compose, the session or the tabs.
 */
internal class FakeAgentHeapDump(
  private val explorer: HeapExplorer,
  override val windowId: String = "testwindow"
) : AgentHeapDump, Closeable {

  override val heapDumpPath: String get() = explorer.heapDumpFile.absolutePath

  override var verdicts: LeakStatusOverrides = LeakStatusOverrides.NONE
    private set

  /** What was written about each place, in the order it was written, so a test can read it back. */
  val notes = mutableMapOf<Place, MutableList<String>>()

  /** The places an agent asked the window to show, in order. */
  val shown = mutableListOf<Place>()

  /** What each read was described as, which is what a session log would have said. */
  val reads = mutableListOf<String>()

  override suspend fun <T> read(
    description: String,
    block: (HeapExplorer) -> T
  ): T {
    reads += description
    // Logged as well as recorded, because the window's own `HeapDumpSession.read` logs every read: what a
    // session log has to show is the reason for a call and then the reads it caused, in that order, and a
    // fake that logged nothing would leave that assertion with only half of what it is about.
    SharkLog.d { description }
    return block(explorer)
  }

  override suspend fun setVerdict(
    verdict: LeakStatusOverride,
    solved: List<LeakStatusOverride>
  ) {
    verdicts = verdicts.with(listOf(verdict) + solved)
  }

  override suspend fun clearVerdict(objectId: Long) {
    verdicts = verdicts.without(objectId)
  }

  override suspend fun appendToNote(
    place: Place,
    text: String
  ) {
    notes.getOrPut(place) { mutableListOf() } += text
  }

  override suspend fun replaceNote(
    place: Place,
    text: String
  ) {
    notes[place] = mutableListOf(text)
  }

  override suspend fun readNote(place: Place): String =
    notes[place]?.joinToString("\n\n").orEmpty()

  override suspend fun notedPlaces(): List<Place> = notes.keys.toList()

  override fun show(place: Place): ShownPlace {
    shown += place
    // A window's answer, which is a link. What a run with no window answers is `HeadlessAgentHeapDumpsTest`'s,
    // since it is that run's one difference from this one.
    return ShownPlace.at("shark://$windowId/${placeText(place)}")
  }

  override fun close() {
    explorer.close()
  }
}

/**
 * The heap dumps of a run, as far as a test needs them: the windows it was given, and nothing plugged in.
 *
 * [AgentHeapDumps] is the app's whole side of this surface — the windows open, plus the two buttons above the
 * map — and a test of what a tool answers has neither a window nor a device. So the dumps are handed in, a
 * dump opened from a path is whatever [opens] makes of it, and `adb` answers with [devices]: enough for a
 * refusal to be a refusal about the right thing, which is what these tools mostly are.
 */
internal class FakeAgentHeapDumps(
  private val open: List<AgentHeapDump> = emptyList(),
  /** Paths this run was pointed at that aren't readable yet, which is a dump still being indexed. */
  private val indexing: List<String> = emptyList(),
  /** Keyed by serial number, each with the processes that device is running. */
  private val devices: Map<AndroidDevice, List<DeviceProcess>> = emptyMap(),
  /** What a file, or a dump pulled off a device, opens as. Refuses by default, since most tests open none. */
  private val opens: (File) -> AgentHeapDump = { file ->
    throw AgentRefusal("This test opens no heap dump, so there is nothing to open $file as.")
  }
) : AgentHeapDumps {

  /** What was asked to be opened, and what was dumped, in order, so a test can read the calls back. */
  val opened = mutableListOf<File>()
  val dumped = mutableListOf<Pair<String, String>>()

  override fun openHeapDumps(): List<AgentHeapDump> = open

  override fun openingHeapDumpPaths(): List<String> = indexing

  override suspend fun open(file: File): AgentHeapDump {
    opened += file
    return opens(file)
  }

  override suspend fun devices(): List<AndroidDevice> = devices.keys.toList()

  override suspend fun processesOf(serialNumber: String): List<DeviceProcess> =
    devices.entries.firstOrNull { it.key.serialNumber == serialNumber }?.value
      ?: throw AgentRefusal("`adb` is connected to no device called \"$serialNumber\".")

  override suspend fun dumpHeap(
    serialNumber: String,
    processName: String
  ): AgentHeapDump {
    processesOf(serialNumber).firstOrNull { it.name == processName }
      ?: throw AgentRefusal("No process called \"$processName\" is running on $serialNumber.")
    dumped += serialNumber to processName
    return opens(File("$processName.hprof"))
  }
}

/**
 * The registry over [heapDumps], with [sessions] as everything agents have recorded on this machine.
 *
 * Sessions are what `agent_log` answers with and nothing else here reads, so a test about any other tool
 * says nothing about them — which is a run that has recorded none, not a run whose log is unreadable.
 */
internal fun agentTools(
  heapDumps: AgentHeapDumps,
  sessions: List<AgentSession> = emptyList()
) = AgentTools(heapDumps) { sessions }
