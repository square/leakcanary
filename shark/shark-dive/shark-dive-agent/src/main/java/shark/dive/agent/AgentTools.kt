package shark.dive.agent

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import shark.dive.DEFAULT_OUTLINE_CHILDREN
import shark.dive.DEFAULT_OUTLINE_DEPTH
import shark.dive.HeapDominatorTreemap
import shark.dive.HeapObjectKind
import shark.dive.LeakStatus
import shark.dive.LeakStatusConflict
import shark.dive.LeakStatusOverride
import shark.dive.ObjectListFilter
import shark.dive.Place
import shark.dive.RootPath
import shark.dive.RootPathStep
import shark.dive.exactHexObjectId
import shark.dive.leakLabel
import shark.dive.leakSolvingProgressRatio
import shark.dive.leakStatusConflictsWith
import shark.dive.nodeIdText
import shark.dive.outlineOf
import shark.dive.suspectReferenceCount
import shark.dive.suspectReferences

/**
 * Everything an agent can do to an open heap dump, as named tools with schemas.
 *
 * Thin on purpose. Shark Dive already answers every question the method asks — a path with its verdicts,
 * every way an object is held, the leaks gathered the way LeakCanary gathers them — so a tool here is a
 * name, a schema and one call into [AgentHeapDump.read].
 *
 * **Nothing here asks an agent what the answer is.** Which reference is at fault is derived from the
 * verdicts recorded about the objects on a path — `shark.dive.faultyReferenceIndexOrNull` — so the work is
 * deciding, object by object, whether that object's own job is done, and the faulty reference falls out of
 * the last verdict that narrows the stretch to one. An agent therefore never types a reference and is never
 * asked to: it types verdicts, each with evidence, and reads [SET_VERDICT]'s answer to see the search narrow.
 *
 * There was a `conclude` here that asked for the root cause and refused until the path named one reference.
 * The refusal was doing real work and the question was not: an agent calling it had already been told the
 * reference by the tool it was about to repeat it to, so what the eval scored was a model's ability to copy a
 * string out of the previous answer. What replaces it is [LEAK_SOLVED] on the answers that can change it —
 * the heap dump saying the search is over, rather than an agent claiming it is. See
 * `shark/shark-dive/notes/agent-surface.md`.
 *
 * What the tools do enforce is the one thing a method cannot: **a verdict needs evidence**, and a verdict
 * contradicting the ones already set has to say so. Enforced by `shark.dive.LeakStatusOverride` and by
 * [SET_VERDICT] refusing conflicts it wasn't told to solve.
 *
 * Every tool also takes a mandatory `reason`, logged beside the reads it caused. That is traceability and
 * not a quality gate — asking a model to explain itself does not make it right — but it is what turns a run
 * into something a person can follow afterwards instead of a conclusion they have to take on trust.
 */
internal class AgentTools(
  private val heapDumps: AgentHeapDumps,
  /**
   * Every session this machine has a record of, for [AGENT_LOG] — the sessions of other runs of the app
   * included, since what has been tried on a heap dump outlives the run it was tried in.
   *
   * A function rather than a list, because it is read off disk per call: an agent asking what has been done
   * to this dump while another one is working on it has to see what that one has done so far.
   */
  private val recordedSessions: () -> List<AgentSession>
) {

  /** In the order an investigation uses them, which is the order a client lists them in. */
  val all: List<AgentTool> = listOf(
    openHeapDump(),
    listHeapDumps(),
    closeHeapDump(),
    heapDumpMetadata(),
    listLeakGroups(),
    agentLog(),
    describeObject(),
    pathFromGcRoot(),
    waysHeld(),
    findObjects(),
    dominatorTree(),
    setVerdict(),
    clearVerdict(),
    readNotes(),
    takeNote(),
    show(),
    listDevices(),
    listProcesses(),
    dumpHeap()
  )

  fun byName(name: String): AgentTool? = all.firstOrNull { it.name == name }

  private fun listHeapDumps() = AgentTool(
    name = LIST_HEAP_DUMPS,
    summary = "Which heap dumps are open, and what every other command names them by.",
    description = "Which heap dumps Shark Dive has open right now. For when nobody told you which dump to " +
      "look at, or when you need the name of one: the file names it hands back are what every other tool " +
      "names a heap dump by, and the verdicts it lists are what somebody has already worked out. If you " +
      "were given a heap dump, call $OPEN_HEAP_DUMP with it instead. That opens the one you name, or " +
      "joins the open of it that is already there. This reads nothing and waits for nothing.",
    schema = schema()
  ) { _ ->
    val dumps = heapDumps.openHeapDumps()
    val indexing = heapDumps.openingHeapDumpPaths()
    val described = dumps.map { describedDump(it) }
    buildJsonObject {
      // Nothing here is a read of a heap dump: a name, a path, the sizes worked out while opening it, the
      // verdicts, and a directory listed for the notes. Which is what makes this the one call that touches
      // every open window and still answers in no time — it used to queue behind each window's current read,
      // and with three dumps open that was 40 seconds of waiting on a dump the agent had not asked about.
      putJsonArray("heapDumps") { described.forEach { add(it) } }
      // The paths this run was started on, whether or not anything is open: a second dump still indexing while
      // the first one is readable is a dump an agent would otherwise never hear about.
      if (indexing.isNotEmpty()) {
        putJsonArray("indexing") { indexing.forEach { add(it) } }
      }
      if (dumps.isEmpty()) {
        put("problem", nothingToRead(indexing))
      }
    }
  }

  private fun openHeapDump() = AgentTool(
    name = OPEN_HEAP_DUMP,
    summary = "Opens a heap dump, or joins the open of it that is already there. Start here.",
    description = "The heap dump you were given, ready to read, and the call every investigation starts " +
      "with. Name it and this answers with it: it opens the file if nobody has it open, and joins the open " +
      "of it that is already there if somebody does, so naming a dump twice never indexes it twice. `$PATH` " +
      "is an absolute `.hprof` path, or the file name of a dump that is already open. The path can be a " +
      "dump a bug report came with, one you took with $DUMP_HEAP, or a second dump of the same app to " +
      "compare against. Opening a large dump takes minutes, and this waits for it, so the key it answers " +
      "with is always one that can be read.",
    schema = schema(
      PATH to string(
        "The absolute path of an `.hprof` file on this machine, or the file name of a heap dump that is " +
          "already open."
      )
    )
  ) { arguments ->
    val path = arguments.string(PATH)
    // A name that is already open before a file to open, because a name is what this surface's own answers
    // are written in: an agent told to investigate `2026-08-31.hprof` has a name and no path, and the dump
    // already open under that name is the answer to it.
    val already = resolvedDump(path)
    val dump = already ?: openFile(path)
    val described = describedDump(dump)
    buildJsonObject {
      described.forEach { (name, value) -> put(name, value) }
      // Whether this opened anything, which is the difference between an agent that has just cost somebody a
      // window and one that joined the window they are watching.
      put("wasAlreadyOpen", already != null)
      put("next", NEXT_WITH_A_NEW_DUMP)
    }
  }

  private fun closeHeapDump() = AgentTool(
    name = CLOSE_HEAP_DUMP,
    summary = "Closes a heap dump, and ends the run when it was the last one open.",
    description = "Closes one heap dump and the window drawing it. An open heap dump is an indexed " +
      "gigabyte and a window on somebody's screen, so close one once you have finished with it. Closing " +
      "the last one ends the run, so a run opened to investigate in goes away instead of waiting for " +
      "whoever is at the machine to notice it. Nothing is lost: the notes and the verdicts are on disk, " +
      "and a `shark://` link to a place of this dump still opens it afterwards. Don't close a dump you " +
      "didn't open unless you were asked to, since somebody may be reading it.",
    schema = schema(HEAP_DUMP to heapDumpArgument())
  ) { arguments ->
    val dump = arguments.heapDump()
    // Before the close, because a key says which of the open dumps this is and after this call it is not one.
    val key = keyOf(dump)
    val path = dump.heapDumpPath
    val wasTheLastOne = heapDumps.openHeapDumps().size == 1
    heapDumps.close(dump)
    buildJsonObject {
      put("closed", key)
      put("heapDumpPath", path)
      // What became of the run, because it is the difference between a command that can be followed by another
      // one and a command after which there is nothing to talk to.
      put("runEnded", wasTheLastOne)
      put(
        "next",
        if (wasTheLastOne) {
          "That was the last heap dump open, so this run is ending: the next $OPEN_HEAP_DUMP starts a new " +
            "one. Your notes and verdicts are on disk and open with the dump."
        } else {
          "Still open: " + openDumpsText(heapDumps.openHeapDumps()) + "."
        }
      )
    }
  }

  /**
   * Opens the file at [path], refusing a path that is no file here and naming what is open instead.
   *
   * The refusal covers both ways in: a path that doesn't exist, and a name nothing open answers to — which
   * reach this from [openHeapDump] as the same argument and are the same mistake with two spellings.
   */
  private suspend fun openFile(path: String): AgentHeapDump {
    val file = File(path)
    if (!file.isFile) {
      val open = heapDumps.openHeapDumps()
      throw AgentRefusal(
        "There is no file at $path, and no heap dump open here is called that. A path is an absolute path " +
          "on the machine Shark Dive is running on, and it has to exist before this can open it; a name is " +
          "the file name of a dump that is already open. " + if (open.isEmpty()) {
          "Nothing is open here at all."
        } else {
          "Open right now: ${openDumpsText(open)}."
        }
      )
    }
    return heapDumps.open(file)
  }

  /**
   * One open heap dump, as both of the calls that name one answer with it.
   *
   * Suspending for the notes, which are a directory listing rather than a read of the heap dump — so the
   * listing of every open dump still waits on nothing, which is the property its own comment is about.
   */
  private suspend fun describedDump(dump: AgentHeapDump): JsonObject {
    val notedPlaces = dump.notedPlaces().size
    val described = AgentJson.heapDump(
      heapDumpKey = keyOf(dump),
      heapDumpPath = dump.heapDumpPath,
      sizes = dump.sizes,
      verdicts = dump.verdicts,
      placesWithANote = notedPlaces
    )
    if (notedPlaces == 0 && dump.verdicts.isEmpty) {
      return described
    }
    return buildJsonObject {
      described.forEach { (name, value) -> put(name, value) }
      // Only when there is something, which is what makes it worth reading. $AGENT_LOG used to recommend
      // itself in its own description, to every agent on every dump, and the recommendation was wrong for
      // nearly all of them: an untouched heap dump is the normal case and a call that answers "nobody has
      // been here" is a call spent on what this field already said.
      put("alreadyWorkedOn", ALREADY_WORKED_ON)
    }
  }

  private fun heapDumpMetadata() = AgentTool(
    name = "heap_dump_metadata",
    summary = "Which Android version and which app this is, and what the heap is made of.",
    description = "What the heap dump says about itself, which is the map LeakCanary prints above a leak " +
      "trace: the API level and the manufacturer of the device, the name of the app's process, the version " +
      "of LeakCanary that wrote the dump, how many classes, instances and arrays are in it, how many " +
      "threads, how many bytes, how many bitmaps and how many of those are bigger than the screen, and the " +
      "SQLite databases the app has open. Call it once before reading any code, because it is where which " +
      "Android version and which app come from. `Build.VERSION.SDK_INT` is the AOSP release to read the " +
      "framework at. `App process name` is the app's package, so the repository and the APK to look for; " +
      "it is `ApplicationInfo.processName`, which is the package unless the app declares an " +
      "`android:process`. ${AgentCommandLine.LEAK_METHOD_OPTION} has the rest of how to pick a version " +
      "to read at. The app's own version number is in no heap dump, so ask whoever gave you this one for " +
      "it. Refused for a dump that is not an Android one, every line of this being read off the Android " +
      "framework. One pass over every object, so ask once.",
    schema = schema(HEAP_DUMP to heapDumpArgument())
  ) { arguments ->
    val dump = arguments.heapDump()
    val metadata = dump.read("the heap dump's metadata, for an agent") { it.readMetadata() }
      ?: throw AgentRefusal(
        "${keyOf(dump)} records no `android.os.Build`, so it is not an Android heap dump and there is " +
          "nothing here to report: the device, the API level, the app's process, the bitmaps and the " +
          "databases are each read off the Android framework. How big this dump is and how its objects " +
          "split up by reachability is in $LIST_HEAP_DUMPS, where the memory has gone is $DOMINATOR_TREE, " +
          "and how many instances of a class there are is $FIND_OBJECTS."
      )
    buildJsonObject {
      // LeakCanary's own keys and its own values, neither renamed nor parsed, which is what makes a figure
      // read here and the same figure in a `leaks.txt` somebody was sent the same figure. See
      // `shark.dive.HeapDive.readMetadata`.
      putJsonObject("metadata") { metadata.forEach { (name, value) -> put(name, value) } }
    }
  }

  private fun listLeakGroups() = AgentTool(
    name = LIST_LEAK_GROUPS,
    summary = "The leaks this heap dump has, each with the objects that leak for that one reason.",
    description = "What this heap dump says shouldn't be in memory: objects the app itself handed to " +
      "LeakCanary and said it was done with, which are the strongest evidence a dump carries, gathered " +
      "into groups, one per leak. Fifty leaked rows of one list are one group and one thing to fix, and " +
      "solving a group means picking a single object in it and solving that one.\n\n" +
      "Each group names a `representativeObject`, the object whose own path produced that group's " +
      "references and leak trace. Pick a group, remember that address, and work it with " +
      "$PATH_FROM_GC_ROOT and $SET_VERDICT: every call you make from here on is about one object you " +
      "chose. Another object of the group will do if you have a reason to prefer it, since they all leak " +
      "for the same reason, but choose one and stay on it.\n\n" +
      "`leakTrace` on each group is the leak trace LeakCanary prints. Show that to a person and quote it " +
      "as it is. Never build a leak trace yourself by joining up class names or steps from this answer: " +
      "one that drops a step or moves the underline looks exactly like the real thing to whoever you gave " +
      "it to. If you have been handed a leak trace by whoever asked you, match it against these.\n\n" +
      "Sections marked isOnTheWayOut are objects the garbage collector will take on its own, so there is " +
      "nothing to investigate there. This is the leak question only: what the memory has gone on is " +
      "$DOMINATOR_TREE. ${AgentCommandLine.LEAK_METHOD_OPTION} is how to work out why one of these is " +
      "still in memory. Read it once, before the first path.",
    schema = schema(HEAP_DUMP to heapDumpArgument())
  ) { arguments ->
    val dump = arguments.heapDump()
    // The traces with the leaks, in the one read: a trace per group is a walk up from one object each, and
    // asking for them afterwards would be a read per group of a list that is usually read once.
    val leaks = dump.read("the leaks, for an agent") { dive ->
      val found = dive.tree.findLeaks(dump.verdicts)
      found to found.sections.flatMap { it.groups }.associate { group ->
        group.leakFingerprint to dive.tree.rootPathTo(group.representativeObjectId, dump.verdicts)
      }
    }
    AgentJson.leaks(leaks.first, leaks.second)
  }

  private fun agentLog() = AgentTool(
    name = AGENT_LOG,
    summary = "What earlier sessions did to this heap dump, call by call.",
    description = "For debugging an agent, not for investigating a heap dump. One entry per session that " +
      "read this dump, newest first, with the reference it solved and how many of its calls were refused. " +
      "With `$SESSION`, every call that session made in order, each with the reason the agent gave and the " +
      "exact text it sent and read back. What that answers is why an investigation went the way it did: a " +
      "conclusion that looks wrong, a run that was abandoned, a step taken on an answer that said nothing. " +
      "It is not how you take up earlier work. What an earlier investigation found is $READ_NOTES, written " +
      "for the next reader; what this dump says shouldn't be in memory is $LIST_LEAK_GROUPS. This is " +
      "somebody else's transcript. The window's Agent logs screen is the same thing for a person.",
    schema = schema(
      HEAP_DUMP to heapDumpArgument(),
      SESSION to string(
        "Optional: one session's id, from the list, to read every call it made, with what each call sent " +
          "and what it got back. The longest answer on this surface, and the only way to tell a step that " +
          "read an answer from one that misread it."
      ).optional()
    )
  ) { arguments ->
    val dump = arguments.heapDump()
    val sessionId = arguments.optionalString(SESSION)
    // The sessions about this heap dump, because a session is only readable against the dump it read: an
    // address in another one is another object. Which is the same rule the screen groups by.
    val sessions = recordedSessions().filter { dump.heapDumpPath in it.heapDumpPaths }
    if (sessionId == null) {
      return@AgentTool buildJsonObject {
        put("heapDumpPath", dump.heapDumpPath)
        putJsonArray("sessions") { sessions.forEach { add(AgentJson.agentSession(it)) } }
        if (sessions.isEmpty()) {
          put("problem", "Nothing has been done to this heap dump through Shark Dive yet, so there is " +
            "nothing to read. What you do now is what the next reader of this will find.")
        }
      }
    }
    val session = sessions.firstOrNull { it.sessionId == sessionId }
      ?: throw AgentRefusal(
        "No session called \"$sessionId\" read this heap dump. " + if (sessions.isEmpty()) {
          "None has: this dump has no agent log yet."
        } else {
          "The ones that did are " + sessions.joinToString(", ") { it.sessionId } + "."
        }
      )
    AgentJson.agentSessionCalls(session)
  }

  private fun describeObject() = AgentTool(
    name = DESCRIBE_OBJECT,
    summary = "One object in full: its class, labels, verdict, sizes and every field.",
    description = "What one object is: its class, what the inspectors made of it, its verdict and the " +
      "reason under it, what it retains, what dominates it, and every field with the address of each " +
      "field's value. Read the fields to turn a guess about an object into evidence.",
    schema = schema(HEAP_DUMP to heapDumpArgument(), OBJECT to objectIdArgument("The object to describe."))
  ) { arguments ->
    val dump = arguments.heapDump()
    val objectId = arguments.objectId(OBJECT)
    dump.read("${exactHexObjectId(objectId)} for an agent") { dive ->
      val tree = dive.tree
      objectId.requireOneObjectOf(tree)
      AgentJson.objectSummary(
        summary = tree.summarize(objectId, dump.verdicts),
        dominator = tree.dominatorOf(objectId)
      )
    }
  }

  private fun pathFromGcRoot() = AgentTool(
    name = PATH_FROM_GC_ROOT,
    summary = "The shortest path of references from a GC root down to one object.",
    description = "The shortest path of references from a GC root down to this object, which is where the " +
      "leak is. Every step carries its verdict, the reason for it, the inspectors' labels and the field " +
      "the step above points through, and steps marked isDominator are the ones every path to the object " +
      "goes through.\n\n" +
      "`$LEAK_SOLVED` is what an investigation works towards. It is true once the verdicts recorded " +
      "about these objects leave exactly one reference that could be at fault, and then `faultyReference` " +
      "names it. Until then `$SUSPECT_REFERENCE_COUNT` is how many are still candidates, and " +
      "`$LEAK_SOLVING_PROGRESS_RATIO` is the share of this path's references your verdicts have ruled " +
      "out, 0 to 1 and not a percentage. You never decide which reference is at fault. You decide, object " +
      "by object, whether that object's own work is done, with $SET_VERDICT, and the last verdict that " +
      "narrows the stretch to one leaves the heap dump naming the reference.\n\n" +
      "`leakTrace` is the leak trace LeakCanary prints, and the only form of it to show a person. Quote " +
      "it as it is. Never assemble a leak trace yourself out of the steps, the class names or anything " +
      "else in this answer: one that drops a step or moves the underline looks exactly like the real " +
      "thing to whoever reads it.",
    schema = schema(HEAP_DUMP to heapDumpArgument(), OBJECT to objectIdArgument("The object to walk up from."))
  ) { arguments ->
    val dump = arguments.heapDump()
    val objectId = arguments.objectId(OBJECT)
    val path = dump.readRootPath(objectId)
    buildJsonObject {
      put("path", AgentJson.rootPath(path))
      put("whatThePathSays", AgentJson.pathVerdicts(path.verdictState()))
      AgentJson.leakTraceText(path)?.let { put("leakTrace", it) }
    }
  }

  private fun waysHeld() = AgentTool(
    name = "ways_held",
    summary = "Every way an object is held, rather than the one path.",
    description = "Every way an object is held, rather than the one path. It answers \"is that reference " +
      "really the only thing keeping it in memory?\", which a single path cannot, and which decides " +
      "whether clearing a field would free anything. Give `from` to ask only about the ways between that " +
      "object and this one.",
    schema = schema(
      HEAP_DUMP to heapDumpArgument(),
      OBJECT to objectIdArgument("The object being held."),
      FROM to objectIdArgument("Optional: only the ways this object holds it, rather than from the GC roots.")
        .optional()
    )
  ) { arguments ->
    val dump = arguments.heapDump()
    val objectId = arguments.objectId(OBJECT)
    val fromObjectId = arguments.optionalObjectId(FROM)
    dump.read("every way ${exactHexObjectId(objectId)} is held, for an agent") { dive ->
      val tree = dive.tree
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
    name = FIND_OBJECTS,
    summary = "The objects whose class name matches, largest retained size first.",
    description = "The objects of this heap dump whose class name matches, largest retained size first, " +
      "with how many matched in total. Use it on a class you have assumed something about: two instances " +
      "of a class you took for a singleton is the answer to a surprising number of leaks, because the " +
      "object on the path then isn't the instance you thought it was. With no className it is every " +
      "object, so it also answers \"what are the biggest things in this heap\", one object at a time. " +
      "$DOMINATOR_TREE answers the same question in terms of what holds them.",
    schema = schema(
      HEAP_DUMP to heapDumpArgument(),
      CLASS_NAME to string("Matched against the class name.").optional(),
      EXACT_MATCH to boolean(
        "Whether className has to be the whole name (`android.graphics.Bitmap`, or `Bitmap`) rather " +
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
    val list = dump.read("the objects matching $filter, for an agent") { dive ->
      dive.tree.listObjects(filter, limit)
    }
    AgentJson.objectList(list)
  }

  private fun dominatorTree() = AgentTool(
    name = DOMINATOR_TREE,
    summary = "Where the memory has gone, as the tree the window draws as a treemap.",
    description = "Where the memory has gone: what holds the most of it, what holds the most of that, and " +
      "so on. The tree the window draws as a treemap, without the pixels. Start at the whole heap dump and " +
      "give `object` to walk down from one node. This answers \"why is this app using 400 MB\". For " +
      "\"why is this object still here\", read its path instead.",
    schema = schema(
      HEAP_DUMP to heapDumpArgument(),
      OBJECT to objectIdArgument("Optional: the node to walk down from, the whole heap dump by default.")
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
    dump.read("the dominator tree under ${nodeIdText(nodeId)}, for an agent") { dive ->
      val tree = dive.tree
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
    summary = "Records that an object is meant to be in memory, or should be gone.",
    description = "Records that an object is meant to be in memory (EXPECTED) or should be gone " +
      "(STUCK), which is how the search narrows: a verdict spreads along every path through that object. " +
      "The `$WHY` is kept with the verdict and is what the next reader has to go on, so write a field " +
      "value or a line of source there and not a hunch. Refuses a verdict that contradicts one already " +
      "set unless solveConflicts is true, in which case the ones it disagrees with are flipped and say " +
      "so.\n\n" +
      "Pass `$SOLVING_LEAK_OF` on every call of a leak investigation. It is the stuck object you chose " +
      "to solve, and it turns the answer into what this verdict did to that leak: the candidate references " +
      "before and after, `$LEAK_SOLVING_PROGRESS_RATIO`, the narrowed suspect path, and `$LEAK_SOLVED` when " +
      "nothing is left to narrow. Without it the answer only says the verdict was recorded, and finding " +
      "out whether you got anywhere costs a $PATH_FROM_GC_ROOT.",
    schema = schema(
      HEAP_DUMP to heapDumpArgument(),
      OBJECT to objectIdArgument("The object to record a verdict about."),
      VERDICT to enumString(
        "STUCK for an object that should be gone, EXPECTED for one that is meant to be here.",
        listOf(LeakStatus.STUCK.name, LeakStatus.EXPECTED.name)
      ),
      WHY to string(
        "The evidence for that verdict, which is kept with it in this heap dump and is what somebody " +
          "reading it next has to check it by: the field value you read, the inspector label, the app's " +
          "own watcher record, the line of source. Not \"probably a cache\". This is the box the window " +
          "labels Why, so what you write here is what the person at the machine reads."
      ),
      SOLVING_LEAK_OF to objectIdArgument(
        "The stuck object whose leak you are solving: the one you picked out of $LIST_LEAK_GROUPS and " +
          "have been working ever since. The answer is then about what this verdict did to that leak. " +
          "It is not the object of this verdict. An object recorded as EXPECTED is above the leak, so " +
          "the path ending at it has nothing stuck on it to point at. This has to be an object the heap " +
          "dump reads as STUCK, which is what makes it a leak to solve."
      ).optional(),
      SOLVE_CONFLICTS to boolean(
        "Whether to flip the verdicts this one contradicts. Ask without it first and read what they are."
      ).optional()
    )
  ) { arguments ->
    val dump = arguments.heapDump()
    val objectId = arguments.objectId(OBJECT)
    val status = arguments.verdict()
    val override = LeakStatusOverride(objectId, status, arguments.string(WHY))
    val solvingLeakOf = arguments.optionalObjectId(SOLVING_LEAK_OF)
    // Read before the verdict is set, because the answer is the difference the verdict made and half of
    // that difference stops existing the moment it is recorded.
    val before = solvingLeakOf?.let { dump.readRootPath(it).requireStuck(it) }
    val conflicts = dump.read(
      "what setting ${exactHexObjectId(objectId)} to $status disagrees with, for an agent"
    ) { dive ->
      objectId.requireOneObjectOf(dive.tree)
      dive.tree.leakStatusConflictsWith(override, dump.verdicts)
    }
    if (conflicts.isNotEmpty() && !arguments.boolean(SOLVE_CONFLICTS, default = false)) {
      throw AgentRefusal(
        "Not set: $status on ${exactHexObjectId(objectId)} contradicts ${conflicts.size} verdict(s) " +
          "already recorded about this heap dump. Everything a stuck object holds is stuck, and " +
          "everything holding an object that is meant to be here is meant to be here, so these cannot " +
          "all be read off one path. Either your verdict is wrong, or theirs is:\n" +
          conflicts.joinToString("\n") { it.asSentence() } +
          "\nCall $SET_VERDICT again with $SOLVE_CONFLICTS true to keep yours and flip those, and say in " +
          "`$WHY` what makes yours the reading to keep."
      )
    }
    dump.setVerdict(override, conflicts.map { it.solved })
    // The path again, because a verdict is only worth setting for what it does to one: this is where an
    // agent sees the unexplained stretch narrow, and where it finds out that a reference is now pointed at.
    val after = solvingLeakOf?.let { dump.readRootPath(it) }
    buildJsonObject {
      put("set", true)
      put("verdictsFlipped", conflicts.size)
      if (after == null || before == null) {
        put(
          "next",
          "Read the path to the stuck object you are solving again with $PATH_FROM_GC_ROOT, since what " +
            "this verdict is worth is what it did to that path. Naming that object as `$SOLVING_LEAK_OF` " +
            "here answers with it, and with what changed."
        )
      } else {
        val state = after.verdictState()
        put(LEAK_SOLVED, state.leakSolved)
        // The pair rather than the new number alone: a verdict that ruled nothing out and a verdict that
        // halved the search are the same single number afterwards, and they are not the same move.
        putJsonObject("narrowedBy") {
          put("suspectReferencesBefore", before.suspectReferenceCount())
          put("suspectReferencesAfter", state.suspectReferenceCount)
          put("progressRatioBefore", AgentJson.roundedRatio(before.leakSolvingProgressRatio()))
          put("progressRatioAfter", AgentJson.roundedRatio(state.progressRatio))
        }
        put("path", AgentJson.rootPath(after))
        put("whatThePathSays", AgentJson.pathVerdicts(state))
        AgentJson.leakTraceText(after)?.let { put("leakTrace", it) }
      }
    }
  }

  /**
   * [this] if the object it leads to is stuck, and a refusal naming what it is instead if it isn't.
   *
   * Because `solvingLeakOf` names *the leak being solved*, and a leak is a stuck object: a path to an object
   * this dump reads as expected, or has nothing recorded about, has no fault on it for a verdict to narrow,
   * so every number the answer would carry about it would be about nothing. Caught here rather than left to
   * read as a progress of 0 that never moves, which is the same answer as a verdict that achieved nothing.
   */
  private fun RootPath.requireStuck(objectId: Long): RootPath {
    val last = steps.lastOrNull()
      ?: throw AgentRefusal(
        "Nothing this heap dump was walked from reaches ${exactHexObjectId(objectId)}, so it is no leak to " +
          "solve. $LIST_LEAK_GROUPS is what this dump says is stuck, each group with the " +
          "representativeObject to work it through."
      )
    if (last.step.leakStatus != LeakStatus.STUCK) {
      throw AgentRefusal(
        "${exactHexObjectId(objectId)} ${last.step.className} is ${last.step.leakStatus.name} in this heap " +
          "dump, and `$SOLVING_LEAK_OF` is the ${LeakStatus.STUCK.name} object whose leak you are solving: " +
          "the one you picked out of $LIST_LEAK_GROUPS, not the object you are setting a verdict on. A " +
          "path ending at an object that isn't stuck has no fault on it to narrow."
      )
    }
    return this
  }

  private fun clearVerdict() = AgentTool(
    name = "clear_verdict",
    summary = "Takes a verdict back off an object.",
    description = "Takes a verdict off an object, so the heap dump says what it says about it again. Use " +
      "it on a verdict of yours that the evidence turned out not to support: while a wrong STUCK is in " +
      "place, everything below it reads as stuck too.",
    schema = schema(HEAP_DUMP to heapDumpArgument(), OBJECT to objectIdArgument("The object to take the verdict off."))
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
      put(WHY, existing.reason)
    }
  }

  private fun readNotes() = AgentTool(
    name = READ_NOTES,
    summary = "What has already been written about this heap dump.",
    description = "What has already been written about this heap dump, by the person at the window, by " +
      "you earlier, or by whoever read it last. Without `$PLACE`, every place that has a note, so that an " +
      "investigation starts from what is already known. With one, that note in full. Notes outlive the " +
      "window and are where a conclusion is kept.",
    schema = schema(HEAP_DUMP to heapDumpArgument(), PLACE to place().optional())
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
    name = TAKE_NOTE,
    summary = "Writes markdown into the notes of one place of this heap dump.",
    description = "Writes markdown into the notes of one place in this heap dump, which is where the " +
      "person at the window reads them and what the next reader of this dump finds. Appends by default, " +
      "leaving whatever was there. `$REPLACE` true puts yours in place of it, which is how to correct " +
      "something you wrote earlier: read it first with $READ_NOTES. Notes are kept between runs of the " +
      "app. Write what you found and where you looked, not what you are about to do.",
    schema = schema(
      HEAP_DUMP to heapDumpArgument(),
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
    name = SHOW,
    summary = "Opens a place in the window, and answers with a link to it.",
    description = "Opens an object in a tab of this window and brings the window to the front, so that what " +
      "you are looking at is what the person at the machine is looking at. Use it when you reach something " +
      "that matters, not at every step. It answers with a `shark://` link to that place: put that link in " +
      "your reply to whoever asked you, because clicking it opens the place again, later, without you. " +
      "`$PLACE` instead of `$OBJECT` shows a screen of this heap dump instead of one object. A run that " +
      "has no window refuses this and puts the link in the refusal, since there is nobody to show it to.",
    schema = schema(
      HEAP_DUMP to heapDumpArgument(),
      OBJECT to objectIdArgument("The object to show, which is what showing something usually is.").optional(),
      PLACE to place().optional()
    )
  ) { arguments ->
    val dump = arguments.heapDump()
    val place = arguments.placeToShow()
    val shown = dump.show(place)
    val problem = shown.problem
    // Refused rather than answered with `shown` false, because being seen is the whole of what this one tool
    // does: an answer is a call that worked to whatever reads it, so an agent would go on to tell its human to
    // look at a window that was never drawn on. Every other tool answers the same with no window; this is the
    // one that can't, which is why the refusal carries the link rather than only the sentence.
    if (problem != null) {
      throw AgentRefusal(listOfNotNull(problem, shown.link?.let { "Link: $it" }).joinToString(" "))
    }
    buildJsonObject {
      put("shown", true)
      put("link", shown.link)
    }
  }

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
      put(HEAP_DUMP, keyOf(dump))
      put("heapDumpPath", dump.heapDumpPath)
      put("dumped", true)
      put("next", NEXT_WITH_A_NEW_DUMP)
    }
  }

  /** The path to [objectId], read through the verdicts, refusing an address that is no object of the dump. */
  private suspend fun AgentHeapDump.readRootPath(objectId: Long): RootPath =
    read("the path to ${exactHexObjectId(objectId)}, for an agent") { dive ->
      objectId.requireOneObjectOf(dive.tree)
      dive.tree.rootPathTo(objectId, verdicts)
    }

  /**
   * Which heap dump and which place a call was about, for the log of the session it was made in.
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
    val dump = read.orNull { optionalString(HEAP_DUMP)?.let { asked -> resolvedDump(asked) } }
    val place = read.orNull { placeOrNull(name) }
    return AgentTarget(
      heapDumpPath = dump?.heapDumpPath,
      place = place
    )
  }

  /** Which heap dump a call is about, or a refusal naming the ones that are open. */
  private fun AgentArguments.heapDump(): AgentHeapDump {
    val asked = string(HEAP_DUMP)
    resolvedDump(asked)?.let { return it }
    val open = heapDumps.openHeapDumps()
    throw AgentRefusal(
      if (open.isEmpty()) {
        "No heap dump is open in Shark Dive, so there is nothing to read. Call $OPEN_HEAP_DUMP with the " +
          "path of the dump to investigate."
      } else {
        "No open heap dump is called \"$asked\". Open right now: ${openDumpsText(open)}. A dump that isn't " +
          "there is $OPEN_HEAP_DUMP away."
      }
    )
  }

  /**
   * The heap dump a call names, and null for one that names none of the open ones.
   *
   * **The file is the whole of how one is named**, by the key it is open under or by the path it was given as.
   * Which is unambiguous because a run opens a file once: [openHeapDump] on a dump this run already has open
   * joins that open rather than making a second, so there is never a second reading of one file here to tell
   * apart. Two readings being compared is two runs, and a command line says which with `--debug-run=`.
   *
   * Both spellings, because both are things an agent has in front of it: somebody says "investigate
   * /tmp/crash-4821.hprof", and a surface that takes only the last part of that is one that makes an agent
   * shorten a path it was handed; while every answer here writes the key, so that is what it can say back.
   */
  private fun resolvedDump(asked: String): AgentHeapDump? = heapDumps.openHeapDumps().keyed()
    .firstOrNull { it.key == asked || it.dump.heapDumpPath == asked }?.dump

  /**
   * The key [dump] is open under, which is what every answer naming a heap dump says. See [keyed].
   *
   * Its own file name for a dump the list has not got. Both ways of opening one put it there before answering,
   * and [CLOSE_HEAP_DUMP] reads its key before closing it, so nothing here reaches that — it is the key a dump
   * of one name would have, which is the answer that cannot be wrong when there is nothing to compare against.
   */
  private fun keyOf(dump: AgentHeapDump): String = heapDumps.openHeapDumps().keyed()
    .firstOrNull { it.dump.heapDumpPath == dump.heapDumpPath }?.key ?: dump.heapDumpName
}

/** One open heap dump and the key this surface names it by. See [keyed]. */
private class KeyedHeapDump(
  val key: String,
  val dump: AgentHeapDump
)

/**
 * The open heap dumps, each with the key every answer names it by: its file name, and `#2` for a second dump
 * of that name.
 *
 * **A file name is what somebody says and what a reply quotes**, so it is what a key is built from. Two open
 * dumps of one name is a real case rather than a hypothetical one — `crash.hprof` pulled off two devices is two
 * files in two directories — and the name on its own left the second one unnameable: it resolved to the first,
 * so a call meant for one was answered about the other and nothing said so.
 *
 * In the order they were opened, which is the order [AgentHeapDumps.openHeapDumps] hands them back, so the
 * first `crash.hprof` keeps the plain name and the next is `crash.hprof#2`. **A key says what is open right
 * now, and a path is the name that doesn't move**: closing the first of two makes the survivor `crash.hprof`
 * again, and a call still saying `#2` is refused with the keys there are rather than answered about the wrong
 * file. [resolvedDump] takes either, so a path is what to hold on to on a run that is closing dumps.
 */
private fun List<AgentHeapDump>.keyed(): List<KeyedHeapDump> {
  val namesSoFar = mutableMapOf<String, Int>()
  return map { dump ->
    val name = dump.heapDumpName
    val ordinal = (namesSoFar[name] ?: 0) + 1
    namesSoFar[name] = ordinal
    KeyedHeapDump(
      key = if (ordinal == 1) name else "$name$KEY_INCREMENT$ordinal",
      dump = dump
    )
  }
}

/**
 * Between a file name and which dump of that name, as in `crash.hprof#2`.
 *
 * A character a shell leaves alone mid-word — `#` starts a comment only at the start of one — so a key can be
 * typed at `--cli` unquoted, which is how every other argument on this surface is typed.
 */
private const val KEY_INCREMENT = '#'

/**
 * What a call was about: which heap dump, and which place of it.
 *
 * Only for the session log, which is the one reader that needs this without needing the answer: a row of the
 * *Agent logs* screen is a verb, a subject and somewhere to go when it is clicked. See [AgentSessionCall].
 */
internal class AgentTarget(
  val heapDumpPath: String?,
  val place: Place?
)

/**
 * What the verdicts on a path add up to: whether one reference is at fault, which references it could be,
 * and what to do next when the path doesn't say which.
 *
 * The same rule `shark.dive.faultyReferenceIndexOrNull` applies, read off the path rather than asked
 * of it, because the ways a path names no reference are different things to do next — and telling an agent
 * which of them it is, is most of what this is worth.
 *
 * **Said in references and in objects, never in a count of steps.** A path narrowed to one object with no
 * verdict has two candidate references, the one into that object and the one out of it, and what decides
 * between them is that object's own verdict — so "one step in between" is a number that reads as an answer
 * and is neither of the two things a reader needs. [suspectReferences] is the candidates, exactly as the
 * leaks screen names them, and [undecided] is the objects to go and decide.
 *
 * [leakSolved] is the one field this surface is worked towards, and it is a fact about the heap dump and the
 * verdicts in it rather than a claim anybody makes. See [AgentTools].
 */
internal class PathVerdicts(
  val faultyStep: RootPathStep?,
  /** Which of the shapes the verdicts are in, which is the field to branch on rather than parse [next]. */
  val state: PathState,
  /** The references the leak could be. See [shark.dive.suspectReferences]. */
  val suspectReferences: List<String>,
  /** The objects between the two verdicts that have none, whose verdicts are what narrow the candidates. */
  val undecided: List<RootPathStep>,
  /** How far the verdicts have narrowed the search. See [shark.dive.leakSolvingProgressRatio]. */
  val progressRatio: Double,
  /** What to do next, and for a solved leak what is left that the heap dump cannot answer. */
  val next: String
) {
  /**
   * Whether this path names the one reference the leak is, which is what an investigation works towards.
   *
   * The same fact as [faultyStep] being non-null, named for what it means rather than for how it is found —
   * and named on the answers, because this is the thing an agent is told to work until it is true.
   */
  val leakSolved: Boolean get() = faultyStep != null

  /** How many references are still candidates, which is 1 for a solved leak. */
  val suspectReferenceCount: Int get() = suspectReferences.size
}

/** The shapes a path's verdicts come in, of which one is an investigation that is over. */
internal enum class PathState {
  NO_PATH,
  NOTHING_STUCK,
  NOTHING_EXPECTED_ABOVE,
  NARROWED,
  REFERENCE_UNREADABLE,
  SOLVED
}

private fun RootPath.verdictState(): PathVerdicts {
  val steps = steps
  val suspects = suspectReferences()
  val progressRatio = leakSolvingProgressRatio()
  if (steps.isEmpty()) {
    return PathVerdicts(
      faultyStep = null,
      state = PathState.NO_PATH,
      suspectReferences = suspects,
      undecided = emptyList(),
      progressRatio = progressRatio,
      next = "Nothing this heap dump was walked from reaches that object, so there is no path to read."
    )
  }
  val firstStuck = steps.indexOfFirst { it.step.leakStatus == LeakStatus.STUCK }
  val lastExpected = steps.indexOfLast { it.step.leakStatus == LeakStatus.EXPECTED }
  if (firstStuck == -1) {
    return PathVerdicts(
      faultyStep = null,
      state = PathState.NOTHING_STUCK,
      suspectReferences = suspects,
      undecided = emptyList(),
      progressRatio = progressRatio,
      next = "No object on this path is ${LeakStatus.STUCK.name}, so there is no fault for a reference to " +
        "be at: one is named only once an object below it is known not to belong. Record the object whose " +
        "work you can show is done as ${LeakStatus.STUCK.name}, and the path narrows from there."
    )
  }
  if (lastExpected == -1) {
    return PathVerdicts(
      faultyStep = null,
      state = PathState.NOTHING_EXPECTED_ABOVE,
      suspectReferences = suspects,
      undecided = emptyList(),
      progressRatio = progressRatio,
      next = "${steps[firstStuck].text()} is ${LeakStatus.STUCK.name} and nothing above it is " +
        "${LeakStatus.EXPECTED.name}, so whatever holds it may be something that should have let go too " +
        "and the fault could be further up than this path reaches. Find the highest object here that is " +
        "meant to be in memory and record it as ${LeakStatus.EXPECTED.name}."
    )
  }
  if (firstStuck != lastExpected + 1) {
    val undecided = (lastExpected + 1 until firstStuck).map { steps[it] }
    return PathVerdicts(
      faultyStep = null,
      state = PathState.NARROWED,
      suspectReferences = suspects,
      undecided = undecided,
      progressRatio = progressRatio,
      next = "The fault is at one of those references, and what settles which is the objects between them " +
        "that have no verdict: one undecided object leaves the reference into it and the reference out of " +
        "it, and its own verdict rules one of them out. They are " +
        undecided.joinToString(", ") { it.text() } + ". So work out whether each of them is done with its " +
        "work, reading its fields with $DESCRIBE_OBJECT and the code that assigns the field holding the " +
        "object below it, and record that with $SET_VERDICT."
    )
  }
  val faulty = steps[firstStuck]
  val reference = faulty.step.reference
    ?: return PathVerdicts(
      faultyStep = null,
      state = PathState.REFERENCE_UNREADABLE,
      suspectReferences = suspects,
      undecided = emptyList(),
      progressRatio = progressRatio,
      next = "${faulty.text()} is the one ${LeakStatus.STUCK.name} object under an " +
        "${LeakStatus.EXPECTED.name} one, but reading the object above it again didn't find the field it " +
        "was reached through, so there is no reference to name. $DESCRIBE_OBJECT on the object above says " +
        "which fields it does have."
    )
  return PathVerdicts(
    faultyStep = faulty,
    state = PathState.SOLVED,
    suspectReferences = suspects,
    undecided = emptyList(),
    progressRatio = progressRatio,
    next = "${reference.leakLabel()} is the faulty reference: it is read on an object meant to be in " +
      "memory and points at one that should be gone. This leak is solved, and what is left is not in the " +
      "heap dump, so there is no further call to make about it. Read the code that assigns that field at the " +
      "version this dump is of, work out what should have cleared it and why it didn't, and tell whoever " +
      "asked you, with the leakTrace below and the `$SHOW` link to this object. $TAKE_NOTE if you want what " +
      "you worked out to be here for the next reader."
  )
}

/** One step of a path as a sentence names it: the address, then the class. */
private fun RootPathStep.text(): String = "${exactHexObjectId(step.objectId)} ${step.className}"

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
 * Refuses an id that is no single object of the heap dump, which is four different mistakes.
 *
 * `summarize` throws on a pile id and on an object the tree has no node for, and the path walks refuse the
 * root, so the alternative to this is a message about the app's internals reaching an agent that asked a
 * reasonable question.
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
        "it was read from, so one copied from another dump, or from another window, names nothing here."
    // An address of a folded object only ever arrives from outside this surface — a note, another tool, a
    // profiler — since nothing here hands one out: a field whose value is folded has no `valueObject`. So
    // it is a reasonable question with an answer, and the answer is that the object it is part of is the
    // one to read.
    this !in tree ->
      "${exactHexObjectId(this)} has its bytes counted inside another object, the way a string's " +
        "characters or a wrapper array's boxed numbers are, so nothing this heap dump was walked from " +
        "points at it and no path reaches it. Read the object it is part of instead."
    else -> return
  }
  throw AgentRefusal(refusal)
}

/**
 * The words this surface is spelled with: the tools something else names, the arguments they take, and the
 * sentences several of them say.
 *
 * Out here rather than inside [AgentTools] because a name belongs to the surface rather than to the class that
 * registers it, and that class is not the only thing here saying one — [nothingToRead], [nothingToShow] and
 * [verdictState] are top level functions writing tool names into their sentences, and [AgentCommandLine] names
 * the ways in and the commands that may start a run.
 *
 * Constants rather than the name written into each sentence, so that renaming a tool is one edit and a sentence
 * pointing at a tool that no longer exists is a compile error. Which is also why only some tools are named
 * here: a tool nothing but its own registration mentions needs no constant, and a list of all twenty would
 * say that every one of them is spoken about somewhere.
 *
 * In the order [AgentTools.all] is in, which is the order an investigation uses them.
 */
internal const val OPEN_HEAP_DUMP = "open_heap_dump"
internal const val LIST_HEAP_DUMPS = "list_heap_dumps"
private const val CLOSE_HEAP_DUMP = "close_heap_dump"
private const val LIST_LEAK_GROUPS = "list_leak_groups"
private const val AGENT_LOG = "agent_log"
private const val DESCRIBE_OBJECT = "describe_object"
private const val FIND_OBJECTS = "find_objects"
private const val DOMINATOR_TREE = "dominator_tree"
private const val PATH_FROM_GC_ROOT = "path_from_gc_root"
private const val SET_VERDICT = "set_verdict"
private const val READ_NOTES = "read_notes"
private const val TAKE_NOTE = "take_note"

/**
 * The field an investigation is worked towards, and the one an agent is told to read rather than to write.
 *
 * Named on the answers of the two calls that can change it — [PATH_FROM_GC_ROOT] and [SET_VERDICT] — and
 * nowhere taken as an argument, which is the whole of the difference between this and the `conclude` it
 * replaced: the heap dump says a leak is solved, and nobody claims it is. See [AgentTools].
 */
private const val LEAK_SOLVED = "leakSolved"
private const val LEAK_SOLVING_PROGRESS_RATIO = "leakSolvingProgressRatio"
private const val SUSPECT_REFERENCE_COUNT = "suspectReferenceCount"
private const val SHOW = "show"
internal const val LIST_DEVICES = "list_devices"
internal const val LIST_PROCESSES = "list_processes"
internal const val DUMP_HEAP = "dump_heap"

/**
 * Which heap dump a call is about: the key it is open under, or the path it was given as.
 *
 * **Required on every command that reads one**, which is what makes each call explicit about the dump it
 * is aimed at. It was optional while only one was open, which read as a convenience and is the one default
 * worth refusing: a second dump opening — from a link, from a person at the machine, from the other agent
 * on the same run — silently turns every call that relied on it into a refusal, and the sessions it
 * happened in are the ones where the agent had the name in its hand all along.
 *
 * **Named `heapDumpKey` rather than `heapDump`**, and so is the field of every answer that hands one back,
 * because they are the same string and were two words for it: an agent reading `heapDump` in an answer and
 * passing `heapDump` to the next call is right, and one reading it as the dump itself and looking for the key
 * elsewhere is reading the word. See [keyed] for what a key is.
 *
 * See [resolvedDump] for what names one, and `shark.dive.DeepLink`, which names a heap dump for the same
 * reason.
 */
private const val HEAP_DUMP = "heapDumpKey"
private const val SESSION = "session"
private const val FROM = "from"
private const val CLASS_NAME = "className"
private const val EXACT_MATCH = "exactMatch"
private const val KINDS = "kinds"
private const val LIMIT = "limit"
private const val VERDICT = "verdict"

/**
 * The evidence a verdict is kept with, which is a different thing from the `reason` a call is made for.
 *
 * Named after the box the window puts it in — `LeakStatusSection`'s `Why` — because that is where what an
 * agent writes here ends up, and the person reading it has the label rather than this schema. It was
 * `reason` for a while, which read as one argument doing two jobs: every other tool's `reason` is why this
 * call was made and goes in the session log, and here it was also the verdict's own justification, kept in
 * the heap dump's `leak-statuses` file for months. Measured on a round of eval runs, a model handed that
 * tool sent both — a long `why` with the field values in it and a one-line `reason` — and had four calls
 * refused for an argument this surface didn't take. Two jobs, so two arguments.
 */
private const val WHY = "why"
private const val SOLVING_LEAK_OF = "solvingLeakOf"
private const val SOLVE_CONFLICTS = "solveConflicts"
private const val TEXT = "text"
private const val REPLACE = "replace"
private const val MAX_DEPTH = "maxDepth"
private const val MAX_CHILDREN = "maxChildren"
private const val PATH = "path"
private const val DEVICE = "device"
private const val PROCESS = "process"

/**
 * What to do with a heap dump that has just been opened, whichever tool opened it.
 *
 * Both questions, because a dump is not always a leak. A dump somebody took because the app was using a
 * gigabyte is a dominator tree, and being pointed only at the leaks is being pointed away from the
 * question — while a dump with `KeyedWeakReference`s in it has an answer waiting in [LIST_LEAK_GROUPS] that
 * walking a tree would take an hour to reach.
 *
 * **And it says where the method is**, that not being in any answer any more:
 * [AgentCommandLine.LEAK_METHOD_OPTION], text this build carries. Opening a dump is the first call of most
 * investigations, which makes this the one answer that can say so to a session that has read nothing — and
 * the whole of what moving the method out of the answers costs is this sentence. See [AgentMethod].
 */
private const val NEXT_WITH_A_NEW_DUMP = "Call $LIST_LEAK_GROUPS with this heap dump for anything about a " +
  "leak, and read ${AgentCommandLine.LEAK_METHOD_OPTION} once before the first path, which is where the " +
  "method is. Call $DOMINATOR_TREE if the question is where the memory has gone."

/**
 * What to do about a heap dump somebody has already worked on, said only when one has — see
 * [describedDump].
 *
 * This is the recommendation [AGENT_LOG] used to make in its own description, where it reached every agent
 * on every dump and was wrong for nearly all of them. It belongs on the answer that knows: the notes and
 * the verdicts are in that answer, so a dump with neither needs no advice about reading them.
 */
private const val ALREADY_WORKED_ON = "Somebody has already worked on this heap dump. Call $READ_NOTES before " +
  "investigating: what they found is either the answer or the half of this dump not worth doing again. " +
  "$AGENT_LOG has the sessions behind it, call by call, which is what to read when their conclusion " +
  "looks wrong."

/** The open heap dumps as a message names them: what to say back, and where each file is. */
private fun openDumpsText(dumps: List<AgentHeapDump>): String =
  dumps.keyed().joinToString(", ") { "${it.key} at ${it.dump.heapDumpPath}" }

/**
 * How many objects a list comes back with by default, well under
 * [HeapDominatorTreemap.MAX_LISTED_OBJECTS]: an agent reads the whole answer, so 500 rows of JSON is
 * mostly context spent on rows nobody asked about. The match count says what was left out.
 */
private const val DEFAULT_LISTED_OBJECTS = 30

private fun heapDumpArgument() = string(
  "Which open heap dump: the `$HEAP_DUMP` of an answer that named one, or the path you were given. " +
    "$LIST_HEAP_DUMPS is the ones there are, and $OPEN_HEAP_DUMP answers with the key of the one it opened."
)

private fun objectIdArgument(description: String) =
  string("$description An address as every answer here spells one: `0x…`.")

/**
 * How far down the dominator tree one call will walk, and how wide.
 *
 * A cap rather than a warning because the answer is a tree: ten levels of ten children is 10^10 nodes,
 * each of them a read of the heap dump, and a model that asked for it would have been waiting for the
 * rest of the day. Five of fifteen is a long screenful.
 */
private const val MAX_OUTLINE_DEPTH = 5
private const val MAX_OUTLINE_CHILDREN = 15

/**
 * Which place of the heap dump a call is about, from what it was given rather than from which tool it is.
 *
 * By argument name, so that a tool added here is described by this without being listed in it: everything
 * about an object takes `object`, everything about a place takes `place`, the search takes a class name and
 * one session of the log takes its id.
 *
 * The tools whose subject is in none of them go through [screenOfTool], which is where the screens an
 * agent names by naming nothing live: the leaks, the metadata, the log as a list, and the two that mean the
 * whole heap dump when they are given no object — the tree from its root, and the object list unfiltered.
 * Every one of them has to be there, because **anything an agent can do that the window can do leads
 * somewhere in the window**: a call with no place is a row of the *Agent logs* screen that shows a reader
 * what was looked at
 * and then declines to show them the thing. The words that row draws come off the same list, so a screen
 * cannot be reachable and unnamed or named and unreachable.
 *
 * What is left with no place is the calls about the app rather than about a heap dump — which dumps are
 * open, which devices are connected, opening a file, taking a dump — and `read_notes` with no place,
 * whose answer is the list of places that have notes, which is the tab strip rather than a screen.
 */
private fun AgentArguments.placeOrNull(name: String): Place? = when {
  optionalString(PLACE) != null -> place()
  optionalString(OBJECT) != null -> Place.Object(objectId(OBJECT))
  optionalString(CLASS_NAME) != null -> Place.Objects(ObjectListFilter(query = string(CLASS_NAME)))
  optionalString(SESSION) != null -> Place.AgentLog(string(SESSION))
  // No arguments, since nothing above matched: what is left is what the tool means on its own.
  else -> screenOfTool(name, emptyMap())?.place
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

/**
 * What `show` was asked to put on screen: an object like every other tool takes, or a screen as a [PLACE].
 *
 * Two arguments for one subject, which nothing else here has, and the reason is measured: `show` is the call an
 * agent makes with an address already in its hand, straight after [DESCRIBE_OBJECT] or [PATH_FROM_GC_ROOT],
 * and it wrote `object=0x…` — the name the rest of the surface uses — at a tool that took `place` alone. So the
 * common case is spelled the common way, and the places that are not one object keep the one vocabulary that
 * names them.
 */
private fun AgentArguments.placeToShow(): Place {
  val objectText = optionalString(OBJECT)
  val placeText = optionalString(PLACE)
  nothingToShow(objectText, placeText)?.let { throw AgentRefusal(it) }
  return if (objectText != null) {
    Place.Object(objectIdOf(OBJECT, objectText))
  } else {
    place()
  }
}

/**
 * Why a `show` call has nothing to open, and null when it names exactly one thing.
 *
 * Either of the two arguments, never both: a call naming an object and a screen has said two things and there
 * is no reading of it that isn't a guess. And a place sent to `$OBJECT` is answered before the address is read,
 * so that a screen named by the wrong argument gets the right argument rather than the refusal for an address
 * that isn't one — see [showItInstead], which is where the words a place is spelled with live.
 */
private fun nothingToShow(
  objectText: String?,
  placeText: String?
): String? = when {
  objectText != null && placeText != null ->
    "$SHOW was given both `$OBJECT` ($objectText) and `$PLACE` ($placeText), which are two places to open " +
      "in one call. Name the one you meant: `$OBJECT` for an object, `$PLACE` for a screen."
  objectText != null -> showItInstead(objectText)?.let { "Nothing shown. $it" }
  placeText != null -> null
  else ->
    "$SHOW needs to be told what to show: `$OBJECT` with an object's `$HEX_PREFIX…` address, which is what " +
      "showing something usually is, or `$PLACE` with a screen of this heap dump. $PLACES_ARE"
}

/**
 * What `list_heap_dumps` answers when there is nothing to read, which depends on whether there is about to be.
 *
 * The indexing case names the path rather than alluding to it, because an agent that is told nothing is open
 * and is not told where its heap dump is has one move left, which is to guess a path — and one guessed another
 * heap dump on the same machine and investigated that instead. See [AgentHeapDumps.openingHeapDumpPaths].
 *
 * **This is a `problem` on an answer and not an [AgentRefusal]**, which reads like an oversight — nothing is
 * open, so the call could not do what it was for — and is the only shape that works. An empty listing *is*
 * the answer to "what is open", and a refusal is text alone: `AgentWire.refusal` carries a message and no
 * answer, and `AgentCommandLine.printed` prints that text to stderr and nothing else. So refusing here would
 * throw away the whole structured answer, starting with the very paths the paragraph above exists to name.
 * Same shape as `agent_log` and [LIST_DEVICES] with nothing to list, and the refusal for a call that needs a
 * dump is [heapDump]'s, which every tool that reads one goes through.
 */
private fun nothingToRead(indexing: List<String>): String = if (indexing.isEmpty()) {
  "No heap dump is open yet. Call $OPEN_HEAP_DUMP with the path of an `.hprof` file, or $DUMP_HEAP to take " +
    "one off a device."
} else {
  "No heap dump can be read yet: this run was pointed at ${indexing.joinToString(", ")} and is indexing it. " +
    "Call $OPEN_HEAP_DUMP with that path, which waits for the indexing instead of starting over, and " +
    "investigate that dump. It is the one you were asked about, so don't go looking for another file."
}
