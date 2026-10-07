package shark.dive.agent

import java.io.Closeable
import java.io.File
import shark.SharkLog
import shark.dive.AndroidDevice
import shark.dive.DeepLink
import shark.dive.DeviceProcess
import shark.dive.HeapDive
import shark.dive.HeapSizes
import shark.dive.VerdictOverride
import shark.dive.VerdictOverrides
import shark.dive.Place

/**
 * A heap dump open the way a window has one, without a window.
 *
 * Which is the whole reason [AgentHeapDump] is an interface: every tool is a read of a heap dump and a write
 * of a verdict or a note, so a test of what a tool answers needs a dump and three fields, and none of
 * Compose, the session or the tabs.
 */
internal class FakeAgentHeapDump(
  private val dive: HeapDive,
  /**
   * Which file this is a reading of, for the tests about two dumps open at once.
   *
   * Two open dumps are two files — `AgentHeapDumps.open` on a file this run already has open joins that open
   * rather than making a second — so a test that needs two needs two names, and it needs nothing else of the
   * second: what it is about is which dump a call was answered about, not what is in either of them.
   */
  private val path: String = dive.heapDumpFile.absolutePath,
  /**
   * What every read waits on first, for the tests about a window that is busy.
   *
   * The app confines reads to the heap dump's own thread and they queue there, so a window in the middle of
   * a leak analysis is a tool call that hasn't come back — which is a state a fake that answers instantly
   * has no way of being in, and one that decides what `list_heap_dumps` is worth.
   */
  private val beforeRead: suspend () -> Unit = {}
) : AgentHeapDump, Closeable {

  override val heapDumpPath: String get() = path

  // Off the dive and not through [read], the way a window's own session hands them over: they were worked out
  // while opening the dump, and a listing of every open dump waits on none of them. See [AgentHeapDump.sizes].
  override val sizes: HeapSizes get() = dive.sizes

  override var verdicts: VerdictOverrides = VerdictOverrides.NONE
    private set

  /** What was written about each place, in the order it was written, so a test can read it back. */
  val notes = mutableMapOf<Place, MutableList<String>>()

  /** The places an agent asked the window to show, in order. */
  val shown = mutableListOf<Place>()

  /** What each read was described as, which is what a session log would have said. */
  val reads = mutableListOf<String>()

  override suspend fun <T> read(
    description: String,
    block: (HeapDive) -> T
  ): T {
    beforeRead()
    reads += description
    // Logged as well as recorded, because the window's own `HeapDumpSession.read` logs every read: what a
    // session log has to show is the reason for a call and then the reads it caused, in that order, and a
    // fake that logged nothing would leave that assertion with only half of what it is about.
    SharkLog.d { description }
    return block(dive)
  }

  override suspend fun setVerdict(
    verdict: VerdictOverride,
    solved: List<VerdictOverride>
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

  override fun show(place: Place): String {
    shown += place
    // A window's answer, which is a link — built the way the window builds one, since a fake that spelled it
    // itself would be a test passing on a link nobody could follow.
    return DeepLink(File(heapDumpPath), place).toUri()
  }

  override fun close() {
    dive.close()
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
  open: List<AgentHeapDump> = emptyList(),
  /** Paths this run was pointed at that aren't readable yet, which is a dump still being indexed. */
  private val indexing: List<String> = emptyList(),
  /** Keyed by serial number, each with the processes that device is running. */
  private val devices: Map<AndroidDevice, List<DeviceProcess>> = emptyMap(),
  /** What a file, or a dump pulled off a device, opens as. Refuses by default, since most tests open none. */
  private val opens: (File) -> AgentHeapDump = { file ->
    throw AgentRefusal("This test opens no heap dump, so there is nothing to open $file as.")
  }
) : AgentHeapDumps {

  /**
   * What is open, which changes while a test runs: closing one is a call on this surface, and what makes
   * `close_heap_dump` worth testing is that the dump it closed is gone from every answer after it.
   */
  private val open = open.toMutableList()

  /** What was asked to be opened, and what was dumped, in order, so a test can read the calls back. */
  val opened = mutableListOf<File>()
  val dumped = mutableListOf<Pair<String, String>>()

  /** The paths that were closed, in order, and whether the run ended — which the last close is. */
  val closed = mutableListOf<String>()
  var runEnded = false
    private set

  override fun openHeapDumps(): List<AgentHeapDump> = open.toList()

  override fun openingHeapDumpPaths(): List<String> = indexing

  override suspend fun open(file: File): AgentHeapDump {
    opened += file
    return opens(file)
  }

  override suspend fun close(dump: AgentHeapDump) {
    val closing = open.firstOrNull { it.heapDumpPath == dump.heapDumpPath }
      ?: throw AgentRefusal("${File(dump.heapDumpPath).name} is not open here, so there is nothing to close.")
    open -= closing
    closed += dump.heapDumpPath
    // The rule the app has on both kinds of run, recorded rather than acted on: a fake that exited the JVM
    // would take the test runner with it. See [AgentHeapDumps.close].
    if (open.isEmpty()) {
      runEnded = true
    }
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
