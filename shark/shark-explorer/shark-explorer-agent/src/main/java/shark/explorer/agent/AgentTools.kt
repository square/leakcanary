package shark.explorer.agent

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import shark.explorer.DEFAULT_OUTLINE_CHILDREN
import shark.explorer.DEFAULT_OUTLINE_DEPTH
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectKind
import shark.explorer.LeakStatus
import shark.explorer.LeakStatusConflict
import shark.explorer.LeakStatusOverride
import shark.explorer.ObjectListFilter
import shark.explorer.Place
import shark.explorer.RootPath
import shark.explorer.RootPathStep
import shark.explorer.exactHexObjectId
import shark.explorer.leakLabel
import shark.explorer.leakStatusConflictsWith
import shark.explorer.nodeIdText
import shark.explorer.outlineOf

/**
 * Everything an agent can do to an open heap dump, as MCP tools.
 *
 * Thin on purpose. The explorer already answers every question the method asks — a chain with its verdicts,
 * every way an object is held, the leaks gathered the way LeakCanary gathers them — so a tool here is a
 * name, a schema and one call into [AgentHeapDump.read]. What the tools add over an API is the two things
 * that make an investigation checkable rather than assertable:
 *
 * - **A verdict needs a reason**, and a reason that contradicts the ones already set has to say so. Enforced
 *   by `shark.explorer.LeakStatusOverride` and by [SET_VERDICT] refusing conflicts it wasn't told to solve.
 * - **[CONCLUDE] is refused until the heap dump agrees** that one reference is at fault. Which is the whole
 *   point: an agent that has narrowed a chain to three unexplained steps cannot report a root cause, however
 *   confident it is, because the software will not let it.
 *
 * Every tool also takes a mandatory `reason`, logged beside the reads it caused. That is traceability and
 * not a quality gate — asking a model to explain itself does not make it right — but it is what turns a run
 * into something a person can follow afterwards instead of a conclusion they have to take on trust.
 */
internal class AgentTools(private val heapDumps: AgentHeapDumps) {

  /** In the order an investigation uses them, which is the order a client lists them in. */
  val all: List<AgentTool> = listOf(
    openHeapDumps(),
    listLeaks(),
    describeObject(),
    chainFromGcRoot(),
    waysHeld(),
    findObjects(),
    dominatorTree(),
    setVerdict(),
    clearVerdict(),
    readNotes(),
    takeNote(),
    show(),
    conclude(),
    openHeapDump(),
    listDevices(),
    dumpHeap()
  )

  fun byName(name: String): AgentTool? = all.firstOrNull { it.name == name }

  private fun openHeapDumps() = AgentTool(
    name = OPEN_HEAP_DUMPS,
    description = "Every heap dump open in Shark Explorer right now, with the method to investigate it. " +
      "Call this first: the window ids it hands back are what every other tool names a heap dump by, and " +
      "the verdicts it lists are the conclusions somebody has already reached about that dump.",
    schema = schema()
  ) { _ ->
    val dumps = heapDumps.openHeapDumps()
    // Read before the JSON is built rather than inside it: a heap dump read suspends, and the JSON builders
    // don't take a suspending block.
    val described = dumps.map { dump ->
      AgentJson.heapDump(
        windowId = dump.windowId,
        heapDumpPath = dump.heapDumpPath,
        sizes = dump.read("its sizes, for an agent") { it.sizes },
        verdicts = dump.verdicts
      )
    }
    buildJsonObject {
      // With the answer rather than only in the handshake, because a client that drops the handshake's
      // instructions is a client whose model never saw them. See [AgentMethod].
      put("method", AgentMethod.INSTRUCTIONS)
      putJsonArray("heapDumps") { described.forEach { add(it) } }
      if (dumps.isEmpty()) {
        put(
          "problem",
          "No heap dump is open yet. Call $OPEN_HEAP_DUMP with the path of an `.hprof` file, or dump_heap " +
            "to take one off a device. If you were pointed at a heap dump, that path is the one to open: it " +
            "may be being indexed right now, and opening it again waits for that rather than starting over."
        )
      }
    }
  }

  private fun listLeaks() = AgentTool(
    name = LIST_LEAKS,
    description = "What this heap dump says shouldn't be in memory, gathered into the leaks those objects " +
      "are instances of. The heap dump's own answer and the place to start: objects the app itself handed " +
      "to LeakCanary and said it was done with are the strongest evidence a dump carries. Sections marked " +
      "isOnTheWayOut are objects the garbage collector will take on its own — not leaks to fix.",
    schema = schema(WINDOW to window())
  ) { arguments ->
    val dump = arguments.heapDump()
    val leaks = dump.read("the leaks, for an agent") { it.tree.findLeaks(dump.verdicts) }
    AgentJson.leaks(leaks)
  }

  private fun describeObject() = AgentTool(
    name = "describe_object",
    description = "What one object is: its class, what the inspectors made of it, its verdict and the " +
      "reason under it, what it retains, what dominates it, and every field with the address of each " +
      "field's value. Reading fields is how a guess about an object becomes evidence.",
    schema = schema(WINDOW to window(), OBJECT to objectId("The object to describe."))
  ) { arguments ->
    val dump = arguments.heapDump()
    val objectId = arguments.objectId(OBJECT)
    dump.read("${exactHexObjectId(objectId)} for an agent") { explorer ->
      val tree = explorer.tree
      objectId.requireOneObjectOf(tree)
      AgentJson.objectSummary(
        summary = tree.summarize(objectId, dump.verdicts),
        dominator = tree.dominatorOf(objectId)
      )
    }
  }

  private fun chainFromGcRoot() = AgentTool(
    name = "chain_from_gc_root",
    description = "The shortest chain of references from a GC root down to this object, which is where the " +
      "leak is. Every step carries its verdict, the reason for it, the inspectors' labels and the field " +
      "the step above points through. faultyReference is what the chain names the leak — the one reference " +
      "to go and change, also marked isFaulty on the step it reaches, and shown as `Leak solved` above the " +
      "chain in the window. It is null while the verdicts don't yet cross from EXPECTED to STUCK at a " +
      "single reference, which is the state an investigation works towards and what conclude requires. " +
      "Steps marked isDominator are the ones every path to the object goes through.",
    schema = schema(WINDOW to window(), OBJECT to objectId("The object to walk up from."))
  ) { arguments ->
    val dump = arguments.heapDump()
    val objectId = arguments.objectId(OBJECT)
    val path = dump.readRootPath(objectId)
    buildJsonObject {
      put("chain", AgentJson.rootPath(path))
      put("whatTheChainSays", path.verdictState().summary)
    }
  }

  private fun waysHeld() = AgentTool(
    name = "ways_held",
    description = "Every way an object is held, rather than the one chain. This is what answers \"is that " +
      "reference really the only thing keeping it in memory?\" — a question a single chain cannot answer, " +
      "and one that decides whether clearing a field would free anything at all. Give `from` to ask only " +
      "about the ways between that object and this one.",
    schema = schema(
      WINDOW to window(),
      OBJECT to objectId("The object being held."),
      FROM to objectId("Optional: only the ways this object holds it, rather than from the GC roots.")
        .optional()
    )
  ) { arguments ->
    val dump = arguments.heapDump()
    val objectId = arguments.objectId(OBJECT)
    val fromObjectId = arguments.optionalObjectId(FROM)
    dump.read("every way ${exactHexObjectId(objectId)} is held, for an agent") { explorer ->
      val tree = explorer.tree
      objectId.requireOneObjectOf(tree)
      val paths = if (fromObjectId == null) {
        tree.independentPathsFromRoots(objectId, dump.verdicts)
      } else {
        fromObjectId.requireOneObjectOf(tree)
        tree.independentPathsBetween(fromObjectId, objectId, dump.verdicts)
      }
      AgentJson.independentPaths(paths)
    }
  }

  private fun findObjects() = AgentTool(
    name = "find_objects",
    description = "The objects of this heap dump whose class name matches, largest retained size first, " +
      "with how many matched in total. Use it on a class you have assumed something about: two instances " +
      "of a class you took for a singleton is the answer to a surprising number of leaks, because the " +
      "object on the chain then isn't the instance you thought it was.",
    schema = schema(
      WINDOW to window(),
      CLASS_NAME to string("Matched against the class name.").optional(),
      EXACT_MATCH to boolean(
        "Whether className has to be the whole name — `android.graphics.Bitmap` or `Bitmap` — rather " +
          "than part of it. Off by default, which finds every class containing it."
      ).optional(),
      KINDS to enumArray(
        "Which kinds of object to list, all of them by default.",
        HeapObjectKind.values().map { it.name }
      ).optional(),
      LIMIT to integer(
        "How many to list, at most ${HeapDominatorTreemap.MAX_LISTED_OBJECTS}. The match count comes " +
          "back whole whatever this is."
      ).optional()
    )
  ) { arguments ->
    val dump = arguments.heapDump()
    val filter = ObjectListFilter(
      query = arguments.optionalString(CLASS_NAME).orEmpty(),
      isExactMatch = arguments.boolean(EXACT_MATCH, default = false),
      kinds = arguments.kinds()
    )
    val limit = arguments.int(LIMIT, default = DEFAULT_LISTED_OBJECTS)
      .coerceIn(1, HeapDominatorTreemap.MAX_LISTED_OBJECTS)
    val list = dump.read("the objects matching $filter, for an agent") { explorer ->
      explorer.tree.listObjects(filter, limit)
    }
    AgentJson.objectList(list)
  }

  private fun dominatorTree() = AgentTool(
    name = "dominator_tree",
    description = "Where the memory has gone: what holds the most of it, what holds the most of that, and " +
      "so on. The tree the window draws as a treemap, without the pixels. Start at the whole heap dump and " +
      "give `object` to walk down from one node. This answers \"why is this app using 400 MB\" — for " +
      "\"why is this object still here\", read its chain instead.",
    schema = schema(
      WINDOW to window(),
      OBJECT to objectId("Optional: the node to walk down from, the whole heap dump by default.")
        .optional(),
      MAX_DEPTH to integer(
        "How many levels down, at most $MAX_OUTLINE_DEPTH. $DEFAULT_OUTLINE_DEPTH by default."
      ).optional(),
      MAX_CHILDREN to integer(
        "How many of each node's biggest children to walk into, at most $MAX_OUTLINE_CHILDREN. " +
          "$DEFAULT_OUTLINE_CHILDREN by default."
      ).optional()
    )
  ) { arguments ->
    val dump = arguments.heapDump()
    val nodeId = arguments.optionalObjectId(OBJECT) ?: HeapDominatorTreemap.ROOT_OBJECT_ID
    val maxDepth = arguments.int(MAX_DEPTH, default = DEFAULT_OUTLINE_DEPTH)
      .coerceIn(0, MAX_OUTLINE_DEPTH)
    val maxChildren = arguments.int(MAX_CHILDREN, default = DEFAULT_OUTLINE_CHILDREN)
      .coerceIn(1, MAX_OUTLINE_CHILDREN)
    dump.read("the dominator tree under ${nodeIdText(nodeId)}, for an agent") { explorer ->
      val tree = explorer.tree
      if (nodeId != HeapDominatorTreemap.ROOT_OBJECT_ID && nodeId !in tree) {
        throw AgentRefusal(
          "${exactHexObjectId(nodeId)} is no node of this heap dump's dominator tree, so there is nothing " +
            "under it to walk. Leave `$OBJECT` out for the whole heap dump."
        )
      }
      AgentJson.dominatorOutline(tree.outlineOf(nodeId, maxDepth, maxChildren))
    }
  }

  private fun setVerdict() = AgentTool(
    name = SET_VERDICT,
    description = "Records that an object is meant to be in memory (EXPECTED) or should be gone " +
      "(STUCK), which is how the search narrows: a verdict spreads along every chain through that " +
      "object, and naming the stuck object you are investigating as `chainTo` answers with what its " +
      "chain says once yours is on it. The `reason` is the " +
      "verdict's reason and is kept with it — make it something the next reader can check, a field value " +
      "or a line of source rather than a hunch. Refuses a verdict that contradicts one already set " +
      "unless solveConflicts is true, in which case the ones it disagrees with are flipped and say so.",
    schema = schema(
      WINDOW to window(),
      OBJECT to objectId("The object to record a verdict about."),
      VERDICT to enumString(
        "STUCK for an object that should be gone, EXPECTED for one that is meant to be here.",
        listOf(LeakStatus.STUCK.name, LeakStatus.EXPECTED.name)
      ),
      CHAIN_TO to objectId(
        "The stuck object you are investigating, which is what the answer reads the chain to: a verdict is " +
          "worth setting for what it does to that chain, and this is where you see the unexplained stretch " +
          "narrow. Not the object of this verdict — one recorded as EXPECTED is above the leak, so the " +
          "chain ending at it has nothing stuck on it to point at."
      ).optional(),
      SOLVE_CONFLICTS to boolean(
        "Whether to flip the verdicts this one contradicts. Ask without it first and read what they are."
      ).optional()
    )
  ) { arguments ->
    val dump = arguments.heapDump()
    val objectId = arguments.objectId(OBJECT)
    val status = arguments.verdict()
    val override = LeakStatusOverride(objectId, status, arguments.reason)
    val conflicts = dump.read(
      "what setting ${exactHexObjectId(objectId)} to $status disagrees with, for an agent"
    ) { explorer ->
      objectId.requireOneObjectOf(explorer.tree)
      explorer.tree.leakStatusConflictsWith(override, dump.verdicts)
    }
    if (conflicts.isNotEmpty() && !arguments.boolean(SOLVE_CONFLICTS, default = false)) {
      throw AgentRefusal(
        "Not set: $status on ${exactHexObjectId(objectId)} contradicts ${conflicts.size} verdict(s) " +
          "already recorded about this heap dump. Everything a stuck object holds is stuck, and " +
          "everything holding an object that is meant to be here is meant to be here, so these cannot " +
          "all be read off one chain. Either your verdict is wrong, or theirs is:\n" +
          conflicts.joinToString("\n") { it.asSentence() } +
          "\nCall $SET_VERDICT again with $SOLVE_CONFLICTS true to keep yours and flip those, and say in " +
          "your reason why."
      )
    }
    dump.setVerdict(override, conflicts.map { it.solved })
    // The chain again, because a verdict is only worth setting for what it does to one: this is where an
    // agent sees the unexplained stretch narrow, and where it finds out that a reference is now pointed at.
    val path = arguments.optionalObjectId(CHAIN_TO)?.let { dump.readRootPath(it) }
    buildJsonObject {
      put("set", true)
      put("verdictsFlipped", conflicts.size)
      if (path == null) {
        put(
          "next",
          "Read the chain to the stuck object you are investigating again with chain_from_gc_root, since " +
            "what this verdict is worth is what it did to that chain. Naming that object as `$CHAIN_TO` " +
            "here answers with it."
        )
      } else {
        val state = path.verdictState()
        put("chain", AgentJson.rootPath(path))
        put("whatTheChainSays", state.summary)
        put("canConclude", state.faultyStep != null)
      }
    }
  }

  private fun clearVerdict() = AgentTool(
    name = "clear_verdict",
    description = "Takes a verdict off an object, so the heap dump says what it says about it again. For " +
      "a verdict of yours that the evidence turned out not to support — leaving a wrong one in place is " +
      "worse than never setting it, because everything below it reads as stuck because of it.",
    schema = schema(WINDOW to window(), OBJECT to objectId("The object to take the verdict off."))
  ) { arguments ->
    val dump = arguments.heapDump()
    val objectId = arguments.objectId(OBJECT)
    val existing = dump.verdicts[objectId]
      ?: throw AgentRefusal(
        "Nothing to clear: no verdict has been recorded about ${exactHexObjectId(objectId)}."
      )
    dump.clearVerdict(objectId)
    buildJsonObject {
      put("cleared", true)
      put("was", existing.status.name)
      put("itsReason", existing.reason)
    }
  }

  private fun readNotes() = AgentTool(
    name = "read_notes",
    description = "What has already been written about this heap dump — by the person at the window, by " +
      "you earlier, or by whoever read it last. Without `$PLACE`, every place that has a note, so that an " +
      "investigation starts from what is known rather than on top of it. With one, that note in full. " +
      "Notes outlive the window and are where a conclusion is kept.",
    schema = schema(WINDOW to window(), PLACE to place().optional())
  ) { arguments ->
    val dump = arguments.heapDump()
    val place = arguments.optionalString(PLACE)?.let { arguments.place() }
    if (place != null) {
      val text = dump.readNote(place)
      return@AgentTool buildJsonObject {
        put("place", arguments.string(PLACE))
        put("characters", text.length)
        put("text", text)
      }
    }
    // Every note of the dump would be a screenful of markdown per place, so this is the listing the tab
    // strip is: where somebody has been, and one call each to read what they wrote.
    val spellings = dump.notedPlaces().mapNotNull { placeText(it) }
    buildJsonObject {
      put("placeCount", spellings.size)
      putJsonArray("places") { spellings.forEach { add(it) } }
      if (spellings.isEmpty()) {
        put("nothingWritten", "Nobody has written anything about this heap dump yet.")
      }
    }
  }

  private fun takeNote() = AgentTool(
    name = "take_note",
    description = "Writes markdown into the notes of one place in this heap dump, which is where the " +
      "person at the window reads them and what the next reader of this dump finds. Appends by default, " +
      "leaving whatever was there; `$REPLACE` true puts yours in place of it, which is what correcting " +
      "something you wrote earlier is — read it first with read_notes. Notes are kept between runs of the " +
      "app. Write what you found and where you looked, not what you are about to do.",
    schema = schema(
      WINDOW to window(),
      PLACE to place(),
      TEXT to string("Markdown. `0x…` addresses in it become links to those objects."),
      REPLACE to boolean(
        "Whether to replace the note rather than add to the end of it. Off by default."
      ).optional()
    )
  ) { arguments ->
    val dump = arguments.heapDump()
    val place = arguments.place()
    val text = arguments.string(TEXT)
    val replaces = arguments.boolean(REPLACE, default = false)
    if (replaces) {
      dump.replaceNote(place, text)
    } else {
      dump.appendToNote(place, text)
    }
    buildJsonObject {
      put("written", true)
      put("replaced", replaces)
    }
  }

  private fun show() = AgentTool(
    name = "show",
    description = "Opens a place in a tab of this window and brings the window to the front, so that what " +
      "you are looking at is what the person at the machine is looking at. Use it when you reach something " +
      "that matters rather than for every step. It answers with a `shark://` link to that place: put that " +
      "link in your reply to whoever asked you, because clicking it opens the place again, later, without " +
      "you.",
    schema = schema(WINDOW to window(), PLACE to place())
  ) { arguments ->
    val dump = arguments.heapDump()
    val place = arguments.place()
    val shown = dump.show(place)
    buildJsonObject {
      put("shown", shown.problem == null)
      put("link", shown.link)
      // So that an agent about to tell its human where to look finds out that there is nowhere.
      put("problem", shown.problem)
    }
  }

  private fun conclude() = AgentTool(
    name = CONCLUDE,
    description = "Reports the root cause of one leak, and the only way to finish an investigation. " +
      "**Refused unless this heap dump agrees that a single reference is at fault**: one object above it " +
      "recorded as EXPECTED, the object below it recorded as STUCK, and nothing unexplained in " +
      "between. If it refuses, the message says what is missing and the investigation is not over. " +
      "Isolating the reference is not the root cause — rootCause is how the field came to still be set, " +
      "which is a sequence of events rather than a line. The conclusion is written into the notes of the " +
      "object and shown in the window.",
    schema = schema(
      WINDOW to window(),
      OBJECT to objectId("The stuck object whose being in memory is being explained."),
      ROOT_CAUSE to string(
        "How the faulty reference came to still be set: what assigned it, what should have cleared it, " +
          "and why it didn't."
      ),
      HOW_TO_REPRODUCE to string(
        "The steps that trigger it, or say that you could not work them out."
      ).optional(),
      NOT_CHECKED to string(
        "What you did not verify. An answer with a stated gap is worth more than one with an unstated gap."
      ).optional()
    )
  ) { arguments ->
    val dump = arguments.heapDump()
    val objectId = arguments.objectId(OBJECT)
    val path = dump.readRootPath(objectId)
    val state = path.verdictState()
    val faulty = state.faultyStep
      ?: throw AgentRefusal(
        "Not concluded. ${state.summary} Until the chain names one reference, a root cause would be a " +
          "guess about which of those steps is at fault. Read the objects in the unexplained stretch with " +
          "describe_object, check whether anything else holds them with ways_held, and record what you " +
          "can defend with $SET_VERDICT."
      )
    val reference = requireNotNull(faulty.step.reference)
    val note = conclusionNote(
      reference = reference.leakLabel(),
      rootCause = arguments.string(ROOT_CAUSE),
      howToReproduce = arguments.optionalString(HOW_TO_REPRODUCE),
      notChecked = arguments.optionalString(NOT_CHECKED),
      reason = arguments.reason
    )
    dump.appendToNote(Place.Object(objectId), note)
    val shown = dump.show(Place.Object(objectId))
    buildJsonObject {
      put("concluded", true)
      putJsonArray("faultyReference") {
        addJsonObject {
          // The words the window names this leak with, so that an answer an agent gives its human and the
          // section at the top of the chain they are looking at are the same string.
          put("reference", reference.leakLabel())
          put("declaredIn", reference.ownerClassName)
          put("field", reference.name)
          put("heldObject", exactHexObjectId(faulty.step.objectId))
          put("heldClassName", faulty.step.className)
          val libraryLeak = reference.libraryLeak
          if (libraryLeak != null) {
            put("libraryLeakPattern", libraryLeak.pattern)
          }
        }
      }
      put(
        "writtenTo",
        "the notes of ${exactHexObjectId(objectId)}" +
          if (shown.problem == null) ", and shown in window ${dump.windowId}" else ". ${shown.problem}"
      )
      // The one link most worth handing back: it opens the object this conclusion is about, with the
      // conclusion in its notes. Say it in your answer rather than describing where to click.
      put("link", shown.link)
    }
  }

  private fun openHeapDump() = AgentTool(
    name = OPEN_HEAP_DUMP,
    description = "Opens a heap dump file in a window of Shark Explorer and answers once it can be read, " +
      "which is the same thing as somebody clicking `Open heap dump…`. For a dump nobody has open yet: a " +
      "file a bug report came with, one you took with dump_heap, or a second dump of the same app to " +
      "compare against. Opening a large dump takes a while, and this waits for it.",
    schema = schema(
      PATH to string("The absolute path of an `.hprof` file on this machine.")
    )
  ) { arguments ->
    val path = arguments.string(PATH)
    val file = File(path)
    if (!file.isFile) {
      throw AgentRefusal(
        "There is no file at $path. A path here is a path on the machine Shark Explorer is running on, " +
          "absolute, and it has to exist before this can open it."
      )
    }
    val dump = heapDumps.open(file)
    buildJsonObject {
      put("window", dump.windowId)
      put("heapDumpPath", dump.heapDumpPath)
      put("opened", true)
      put("next", "Call $LIST_LEAKS with this window to see what the dump says about itself.")
    }
  }

  private fun listDevices() = AgentTool(
    name = "list_devices",
    description = "The Android devices `adb` is connected to, and with `$DEVICE`, the app processes of one " +
      "of them. What the window's `Take heap dump` button asks. A process can only be dumped if the app " +
      "was built debuggable or the whole build is (dumpsAnyProcess), and `adb` is the only thing here that " +
      "reaches outside this machine.",
    schema = schema(
      DEVICE to string("Optional: a device's serial number, to list its app processes.").optional()
    )
  ) { arguments ->
    val serialNumber = arguments.optionalString(DEVICE)
    if (serialNumber == null) {
      val devices = heapDumps.devices()
      return@AgentTool buildJsonObject {
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
    val processes = heapDumps.processesOf(serialNumber)
    buildJsonObject {
      put("device", serialNumber)
      putJsonArray("processes") { AgentJson.processes(processes).forEach { add(it) } }
    }
  }

  private fun dumpHeap() = AgentTool(
    name = "dump_heap",
    description = "Takes a heap dump of a running process, opens it in a window of Shark Explorer and " +
      "answers once it can be read — the whole of what the window's `Take heap dump` button does. " +
      "**Minutes, on a large app**: the device writes the dump, it is pulled over `adb`, and then opened. " +
      "The garbage is collected first where the device is new enough, so what is in the dump is what is " +
      "really still held. Ask list_devices first for the device and the process.",
    schema = schema(
      DEVICE to string("The serial number of the device, from list_devices."),
      PROCESS to string("The name of the process to dump, from list_devices.")
    )
  ) { arguments ->
    val dump = heapDumps.dumpHeap(
      serialNumber = arguments.string(DEVICE),
      processName = arguments.string(PROCESS)
    )
    buildJsonObject {
      put("window", dump.windowId)
      put("heapDumpPath", dump.heapDumpPath)
      put("dumped", true)
      put("next", "Call $LIST_LEAKS with this window to see what the dump says about itself.")
    }
  }

  /** The chain to [objectId], read through the verdicts, refusing an address that is no object of the dump. */
  private suspend fun AgentHeapDump.readRootPath(objectId: Long): RootPath =
    read("the chain to ${exactHexObjectId(objectId)}, for an agent") { explorer ->
      objectId.requireOneObjectOf(explorer.tree)
      explorer.tree.rootPathTo(objectId, verdicts)
    }

  /**
   * Which window and which place a call was about, for the log of the session it was made in.
   *
   * Read off the arguments rather than out of the handler, so that a call that was refused is recorded
   * pointing at whatever it was asking about — which is most of what makes a refusal worth reading
   * afterwards. Nothing here refuses: this is a description of a call, and a call with an argument this
   * can't make sense of is one the handler is about to refuse with a message of its own.
   */
  fun target(
    name: String,
    arguments: JsonObject
  ): AgentTarget {
    val read = AgentArguments(name, arguments)
    val dump = read.orNull { resolvedDump(optionalString(WINDOW)) }
    val place = read.orNull { placeOrNull(name) }
    return AgentTarget(
      windowId = dump?.windowId,
      heapDumpPath = dump?.heapDumpPath,
      place = place
    )
  }

  /**
   * Which place of the heap dump a call is about, from what it was given rather than from which tool it is.
   *
   * By argument name, so that a tool added here is described by this without being listed in it: everything
   * about an object takes `object`, everything about a place takes `place`, and the search takes a class
   * name. The one tool whose subject is in neither is the list of leaks, which takes nothing at all.
   */
  private fun AgentArguments.placeOrNull(name: String): Place? = when {
    optionalString(PLACE) != null -> place()
    optionalString(OBJECT) != null -> Place.Object(objectId(OBJECT))
    optionalString(CLASS_NAME) != null -> Place.Objects(ObjectListFilter(query = string(CLASS_NAME)))
    name == LIST_LEAKS -> Place.Leaks()
    else -> null
  }

  /** Which heap dump a call is about, or a refusal naming the ones that are open. */
  private fun AgentArguments.heapDump(): AgentHeapDump {
    val windowId = optionalString(WINDOW)
    val asked = resolvedDump(windowId)
    if (asked != null) {
      return asked
    }
    val open = heapDumps.openHeapDumps()
    val windows = open.joinToString(", ") { "${it.windowId} (${it.heapDumpPath})" }
    throw AgentRefusal(
      when {
        open.isEmpty() ->
          "No heap dump is open in Shark Explorer, so there is nothing to read. Call $OPEN_HEAP_DUMPS."
        windowId == null ->
          "${open.size} heap dumps are open, so say which with `$WINDOW`: $windows"
        else ->
          "No window is called \"$windowId\". A window id names one window of one run of this app, so it " +
            "stops being valid when that window is closed. Open windows: $windows. Call $OPEN_HEAP_DUMPS."
      }
    )
  }

  /**
   * The window a call names, and null for one that names none of the open ones.
   *
   * One open dump needs no naming, which is most sessions. Two of them always do: the same file open twice is
   * how two readings of it are compared, so guessing would be answering about the wrong one.
   */
  private fun resolvedDump(windowId: String?): AgentHeapDump? {
    val open = heapDumps.openHeapDumps()
    return if (windowId == null) {
      open.singleOrNull()
    } else {
      open.firstOrNull { it.windowId == windowId }
    }
  }

  /**
   * Whatever [block] reads, or null if the arguments wouldn't answer it.
   *
   * Only for [target], and that is the whole of why it exists: describing a call must not refuse one. The
   * handler reads the same arguments a moment later and refuses with a message written for the agent, which
   * is where a bad address belongs.
   */
  private fun <T> AgentArguments.orNull(block: AgentArguments.() -> T?): T? = try {
    block()
  } catch (refused: AgentRefusal) {
    null
  }

  private fun AgentArguments.verdict(): LeakStatus {
    val text = string(VERDICT)
    val status = LeakStatus.values().firstOrNull { it.name.equals(text, ignoreCase = true) }
      ?: throw AgentRefusal(
        "\"$text\" is no verdict. It is ${LeakStatus.STUCK.name} for an object that should be gone or " +
          "${LeakStatus.EXPECTED.name} for one that is meant to be here."
      )
    if (status == LeakStatus.UNKNOWN) {
      throw AgentRefusal(
        "${LeakStatus.UNKNOWN.name} is what an object with no verdict already is, so setting it says " +
          "nothing. To take a verdict back off an object, call clear_verdict."
      )
    }
    return status
  }

  private fun AgentArguments.kinds(): Set<HeapObjectKind> {
    val names = stringList(KINDS) ?: return HeapObjectKind.values().toSet()
    return names.map { name ->
      HeapObjectKind.values().firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: throw AgentRefusal(
          "\"$name\" is no object kind. They are " +
            HeapObjectKind.values().joinToString(", ") { it.name } + "."
        )
    }.toSet()
  }

  private companion object {

    /** What every investigation starts with, named because three messages point at it. */
    const val OPEN_HEAP_DUMPS = "open_heap_dumps"
    const val SET_VERDICT = "set_verdict"
    const val CONCLUDE = "conclude"

    /** Named because [placeOrNull] is the one description of a call that has to know which tool it is. */
    const val LIST_LEAKS = "list_leaks"

    /** And because the refusal for a path that isn't a heap dump points at it. */
    const val OPEN_HEAP_DUMP = "open_heap_dump"

    const val WINDOW = "window"
    const val OBJECT = "object"
    const val FROM = "from"
    const val CLASS_NAME = "className"
    const val EXACT_MATCH = "exactMatch"
    const val KINDS = "kinds"
    const val LIMIT = "limit"
    const val VERDICT = "verdict"
    const val CHAIN_TO = "chainTo"
    const val SOLVE_CONFLICTS = "solveConflicts"
    const val TEXT = "text"
    const val REPLACE = "replace"
    const val MAX_DEPTH = "maxDepth"
    const val MAX_CHILDREN = "maxChildren"
    const val PATH = "path"
    const val DEVICE = "device"
    const val PROCESS = "process"
    const val ROOT_CAUSE = "rootCause"
    const val HOW_TO_REPRODUCE = "howToReproduce"
    const val NOT_CHECKED = "notChecked"

    /**
     * How many objects a list comes back with by default, well under
     * [HeapDominatorTreemap.MAX_LISTED_OBJECTS]: an agent reads the whole answer, so 500 rows of JSON is
     * mostly context spent on rows nobody asked about. The match count says what was left out.
     */
    const val DEFAULT_LISTED_OBJECTS = 30

    fun window() = string(
      "Which open heap dump, from ${OPEN_HEAP_DUMPS}. Optional while only one is open."
    ).optional()

    fun objectId(description: String) =
      string("$description An address as ${OPEN_HEAP_DUMPS} and every chain spells one: `0x…`.")

    /**
     * How far down the dominator tree one call will walk, and how wide.
     *
     * A cap rather than a warning because the answer is a tree: ten levels of ten children is 10^10 nodes,
     * each of them a read of the heap dump, and a model that asked for it would have been waiting for the
     * rest of the day. Five of fifteen is a long screenful.
     */
    const val MAX_OUTLINE_DEPTH = 5
    const val MAX_OUTLINE_CHILDREN = 15
  }
}

/**
 * What a call was about: which window, which heap dump, and which place of it.
 *
 * Only for the session log, which is the one reader that needs this without needing the answer: a row of the
 * *Agent logs* screen is a verb, a subject and somewhere to go when it is clicked. See [AgentSessionCall].
 */
internal class AgentTarget(
  val windowId: String?,
  val heapDumpPath: String?,
  val place: Place?
)

/**
 * What the verdicts on a chain add up to: whether one reference is at fault, and what to say when none is.
 *
 * The same rule `shark.explorer.faultyReferenceIndexOrNull` applies, read off the chain rather than asked
 * of it, because the three ways a chain names no reference are three different things to do next — and
 * telling an agent which of them it is, is most of what [AgentTools.CONCLUDE] refusing is worth.
 */
private class ChainVerdicts(
  val faultyStep: RootPathStep?,
  val summary: String
)

private fun RootPath.verdictState(): ChainVerdicts {
  val steps = steps
  if (steps.isEmpty()) {
    return ChainVerdicts(
      faultyStep = null,
      summary = "Nothing this heap dump was walked from reaches that object, so there is no chain to read."
    )
  }
  val firstStuck = steps.indexOfFirst { it.step.leakStatus == LeakStatus.STUCK }
  val lastExpected = steps.indexOfLast { it.step.leakStatus == LeakStatus.EXPECTED }
  if (firstStuck == -1) {
    return ChainVerdicts(
      faultyStep = null,
      summary = "Nothing on this chain of ${steps.size} steps is ${LeakStatus.STUCK.name}, so it points " +
        "at no reference: the rules can only name one once something below it is known not to belong."
    )
  }
  if (lastExpected == -1) {
    return ChainVerdicts(
      faultyStep = null,
      summary = "The chain has a ${LeakStatus.STUCK.name} object at step ${firstStuck + 1} of " +
        "${steps.size} and nothing above it is ${LeakStatus.EXPECTED.name}. So whatever holds it may " +
        "be something that should have let go too, and the fault could be further up than this chain " +
        "knows: find the highest object here that is meant to be in memory and record it."
    )
  }
  if (firstStuck != lastExpected + 1) {
    val unexplained = (lastExpected + 1 until firstStuck).map { steps[it] }
    return ChainVerdicts(
      faultyStep = null,
      summary = "${unexplained.size} step(s) between the last ${LeakStatus.EXPECTED.name} object and " +
        "the first ${LeakStatus.STUCK.name} one have no verdict, so the fault is at one of them and the " +
        "chain doesn't say which: " +
        unexplained.joinToString(", ") { "${exactHexObjectId(it.step.objectId)} ${it.step.className}" } +
        "."
    )
  }
  val faulty = steps[firstStuck]
  val reference = faulty.step.reference
    ?: return ChainVerdicts(
      faultyStep = null,
      summary = "One reference crosses from ${LeakStatus.EXPECTED.name} to " +
        "${LeakStatus.STUCK.name} here, but reading the object above again didn't find the field it was " +
        "reached through, so there is no reference to name."
    )
  return ChainVerdicts(
    faultyStep = faulty,
    summary = "${reference.leakLabel()} is the faulty reference: the one step from " +
      "an object meant to be in memory to one that should be gone."
  )
}

/** What [AgentTools.CONCLUDE] writes into the notes, which is the investigation's answer where it belongs. */
private fun conclusionNote(
  reference: String,
  rootCause: String,
  howToReproduce: String?,
  notChecked: String?,
  reason: String
): String = buildString {
  appendLine("## Root cause")
  appendLine()
  appendLine("**Faulty reference:** `$reference`")
  appendLine()
  appendLine(rootCause)
  if (howToReproduce != null) {
    appendLine()
    appendLine("**How to reproduce:** $howToReproduce")
  }
  if (notChecked != null) {
    appendLine()
    appendLine("**Not checked:** $notChecked")
  }
  appendLine()
  appendLine("_Concluded by an agent: ${reason}_")
}

/**
 * One verdict a new one disagrees with, as a line of the refusal that says so.
 *
 * A sentence rather than the JSON this used to be. Not for the model's sake — it reads either — but because
 * a refusal is the one answer on this surface that is also read by a person: it is what the window's *Agent
 * logs* screen draws under the call it refused, and a JSON array of three verdicts with their reasons in it
 * is the raw protocol on a screen that exists to not show it.
 *
 * Which way round the two objects are is in it, since that is what makes the disagreement one at all.
 */
private fun LeakStatusConflict.asSentence(): String {
  val side = if (isAbove) "which holds it" else "which it holds"
  return "- ${exactHexObjectId(existing.objectId)} $objectName, $side, is ${existing.status}: " +
    "${existing.reason} Keeping yours makes it ${solved.status}."
}

/**
 * Refuses an id that is no single object of the heap dump, which is three different mistakes.
 *
 * `summarize` throws on a pile id and the chain walks refuse the root, so the alternative to this is a
 * message about the app's internals reaching an agent that asked a reasonable question.
 */
private fun Long.requireOneObjectOf(tree: HeapDominatorTreemap) {
  val refusal = when {
    this == HeapDominatorTreemap.ROOT_OBJECT_ID ->
      "${exactHexObjectId(this)} is the whole heap dump rather than an object of it, so there is nothing " +
        "to read about it."
    HeapDominatorTreemap.isPileId(this) ->
      "${exactHexObjectId(this)} stands for a pile of small objects the map had no room to draw, rather " +
        "than for one object. Name one of the objects instead."
    tree.objectNameOrNull(this) == null ->
      "${exactHexObjectId(this)} is no object of this heap dump. An address is only an address of the dump " +
        "it was read from, so one copied from another dump — or from another window — names nothing here."
    else -> return
  }
  throw AgentRefusal(refusal)
}
