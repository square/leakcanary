package shark.dive.agent

import java.io.Closeable
import java.io.File
import shark.SharkLog
import shark.dive.AndroidDevice
import shark.dive.DeepLink
import shark.dive.DeviceProcess
import shark.dive.HeapDive
import shark.dive.HeapDominatorTreemap
import shark.dive.LeakStatusOverride
import shark.dive.LeakStatusOverrides
import shark.dive.Place
import shark.dive.exactHexObjectId
import shark.dive.nodeIdText
import shark.dive.titleOf

/**
 * A heap dump open the way a window has one, without a window.
 *
 * Which is the whole reason [AgentHeapDump] is an interface: every tool is a read of a heap dump and a write
 * of a verdict or a note, so a test of what a tool answers needs a dump and three fields, and none of
 * Compose, the session or the tabs.
 */
internal class FakeAgentHeapDump(
  private val dive: HeapDive,
  override val windowId: String = "testwindow"
) : AgentHeapDump, Closeable {

  override val heapDumpPath: String get() = dive.heapDumpFile.absolutePath

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
    block: (HeapDive) -> T
  ): T {
    reads += description
    // Logged as well as recorded, because the window's own `HeapDumpSession.read` logs every read: what a
    // session log has to show is the reason for a call and then the reads it caused, in that order, and a
    // fake that logged nothing would leave that assertion with only half of what it is about.
    SharkLog.d { description }
    return block(dive)
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

  /** What was asked to be drawn, in order, as `<width>x<height> under <address>`. */
  val drawn = mutableListOf<String>()

  /**
   * The read the app's implementation makes, and bytes standing in for the document it would write.
   *
   * A document is Remote Compose, which `shark-dive-app` depends on and nothing here does, so what a test of
   * these tools can be about is which drawing was asked for and where its bytes went — never what is in them.
   * `TreemapDocumentTest` is the one that writes a real document and reads it back.
   *
   * The refusal is here rather than only in the app because it is the contract of
   * [AgentHeapDump.drawTreemap] rather than a detail of one implementation: a `resources/read` can name any
   * address, and drawing the whole heap dump for one that is no node would be a picture of everything
   * answered to a question about one thing.
   */
  override suspend fun drawTreemap(
    rootObjectId: Long,
    width: Int,
    height: Int
  ): TreemapDrawing = read("the treemap under ${nodeIdText(rootObjectId)}, to draw for an agent") { dive ->
    drawn += "${width}x$height under ${exactHexObjectId(rootObjectId)}"
    if (rootObjectId != HeapDominatorTreemap.ROOT_OBJECT_ID && rootObjectId !in dive.tree) {
      throw AgentRefusal("${exactHexObjectId(rootObjectId)} is no node of this heap dump's dominator tree.")
    }
    TreemapDrawing(
      document = "a drawing of ${exactHexObjectId(rootObjectId)}".toByteArray(),
      title = dive.tree.titleOf(Place.Object(rootObjectId))
    )
  }

  override fun show(place: Place): ShownPlace {
    shown += place
    // A window's answer, which is a link — built the way the window builds one, since a fake that spelled it
    // itself would be a test passing on a link nobody could follow. What a run with no window answers is
    // `HeadlessAgentHeapDumpsTest`'s, since it is that run's one difference from this one.
    return ShownPlace.at(DeepLink(File(heapDumpPath), place).toUri())
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
