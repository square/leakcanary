package shark.dive.agent

import kotlin.math.round
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import shark.dive.AndroidDevice
import shark.dive.DeviceProcess
import shark.dive.DominatorOutline
import shark.dive.GcRootReferrer
import shark.dive.HeapLeaks
import shark.dive.HeapObjectSummary
import shark.dive.HeapSizes
import shark.dive.IndependentPaths
import shark.dive.LeakKind
import shark.dive.VerdictOverrides
import shark.dive.ObjectContent
import shark.dive.ObjectDominator
import shark.dive.ObjectList
import shark.dive.ObjectListEntry
import shark.dive.ObjectReferrer
import shark.dive.ObjectReferrers
import shark.dive.PathReference
import shark.dive.PathStep
import shark.dive.ReachabilityStrength
import shark.dive.RootPath
import shark.dive.RootPathStep
import shark.dive.exactHexObjectId
import shark.dive.leakLabel
import shark.dive.leakTrace
import shark.dive.suspectReferenceIndexes

/**
 * How Shark Dive's own model reads as JSON, which is the whole of what an agent sees of a heap dump.
 *
 * Two rules run through all of it, and both are about being read by something that is not this app.
 *
 * **An address is a string, never a number.** A heap dump's addresses fill the whole range of `Long`, and a
 * JSON number is a double to most clients of this protocol — anything above 2^53 comes back rounded, which
 * for an address means a different object, silently. So every one of them is [exactHexObjectId], the same
 * spelling this app's own files use, and [shark.dive.objectIdOfHex] is the only way back.
 *
 * **Nothing is summarised away, and every cap says so.** A field an agent can't see is a field it will
 * guess at, so the answers here are what the window shows: the labels the inspectors wrote, the verdict and
 * the reason under it, the whole path. Where an answer is capped — a list of objects, the ways one is held
 * — the count that was matched and whether anything was left out go with it, because an agent counting the
 * instances of a class must never be counting the page it was shown.
 */
internal object AgentJson {

  /**
   * Which heap dump, how big it is, and what has been concluded about it so far.
   *
   * The key first because it is what every other call names this dump by, under the same name there as here, and
   * the path after it, since a key is only unambiguous within one run and a path is what a reply quotes.
   *
   * None of it is read from the heap dump: the sizes were worked out while opening it and the verdicts and the
   * notes are on disk — [placesWithANote] is a directory listing — which is what lets the listing of every
   * open dump wait on none of them. See [AgentHeapDump.sizes].
   */
  fun heapDump(
    heapDumpKey: String,
    heapDumpPath: String,
    sizes: HeapSizes,
    verdicts: VerdictOverrides,
    /** How many places of this dump somebody has written about, as [AgentHeapDump.notedPlaces] counts them. */
    placesWithANote: Int
  ): JsonObject = buildJsonObject {
    put("heapDumpKey", heapDumpKey)
    put("heapDumpPath", heapDumpPath)
    putJsonObject("sizes") {
      put("totalBytes", sizes.totalByteCount)
      put("totalObjects", sizes.totalObjectCount)
      // What a retained size is a share of, and the number an agent should compare one against.
      put("stronglyReachableBytes", sizes.stronglyReachableByteCount)
      put("unreachableBytes", sizes.unreachableByteCount)
      putJsonArray("byStrength") {
        ReachabilityStrength.values().forEach { strength ->
          addJsonObject {
            put("strength", strength.name)
            put("bytes", sizes.byteCountByStrength.getValue(strength))
            put("objects", sizes.objectCountByStrength.getValue(strength))
          }
        }
      }
    }
    put("verdictsSetByHand", verdicts(verdicts))
    // Whether anybody has been here, which is the question every investigation opens with and whose answer is
    // nearly always nobody. This and the verdicts above are what work on a heap dump leaves behind — the
    // verdicts are what solved a leak and a note is what somebody chose to write beside one — so an agent
    // that reads them here spends no call finding out there is nothing to read.
    put("placesWithANote", placesWithANote)
  }

  /**
   * One investigation somebody else already ran: who was working, how it went, and what it came to.
   *
   * The numbers the *Agent logs* screen shows in the same order, because they are what makes a session worth
   * opening or not: how much was asked, how much of it was refused, and whether it solved a leak.
   */
  fun agentSession(session: AgentSession): JsonObject = buildJsonObject {
    put("session", session.sessionId)
    put("startedAt", session.startedAt?.toString())
    // The calls that reached a tool, and not every line of it: a line naming a tool this build hasn't is not
    // a step of an investigation, and it is in the session form below. See [AgentSession.toolCalls].
    put("calls", session.toolCalls.size)
    put("refused", session.refusedCount)
    // And how many got no answer at all, which is this app failing rather than the surface saying no.
    put("errors", session.errorCount)
    // Which reference its verdicts came to, which is the one thing a reader is looking for — and null for a
    // session that solved nothing, which is most of them. Derived by Shark Dive from the verdicts the
    // session recorded rather than said by the agent, see `AgentSessionFile.outcomeOfTool`.
    put("solved", session.calls.mapNotNull { it.outcome }.lastOrNull())
    putJsonArray("heapDumps") { session.heapDumpPaths.forEach { add(it) } }
  }

  /**
   * Every call of one session, in the order it made them, with the reason the agent gave for each — and the
   * exchange itself.
   *
   * The reasons are the point. A session read as a list of tool names is the protocol showing through; read
   * as what was asked and why, it either follows from itself or doesn't — which is the same judgement the
   * person at the window makes on that screen.
   *
   * **And `input` and `output` are what the reasons are checked against**, which is why this answer is a long
   * one: a reason is what the agent said it was doing, and the two of them are what it actually sent and
   * actually read. An agent asked to work out where another one went wrong cannot do it from a summary,
   * however well worded — the step that misread an answer reads exactly like the step that read it right.
   * The same text the window's *Agent logs* screen unfolds a row onto.
   *
   * So a session with a hundred calls in it is a large answer, and that is the tool being used as intended
   * rather than a leak: this is the only call on this surface whose subject is somebody else's whole
   * investigation. The list without `session` is the short form, and how a reader picks which one to read.
   */
  fun agentSessionCalls(session: AgentSession): JsonObject = buildJsonObject {
    put("session", session.sessionId)
    putJsonArray("calls") {
      session.calls.forEach { call ->
        addJsonObject {
          put("at", call.at.toString())
          // Null on `tool` is a line that reached none, and `input` is then the whole of what it was. Not only
          // the calls, because a session that shows the ones that worked cannot answer why the others didn't.
          // See [AgentSession.calls].
          put("tool", call.tool)
          put("reason", call.reason)
          // What the call was about, as the agent wrote it: an address is that dump's address, and this is
          // read by something that can resolve it.
          put("about", call.subject)
          put("heapDumpPath", call.heapDumpPath)
          put("refused", call.refusal)
          // And why nothing could be answered at all, which is a different thing from being told no.
          put("error", call.error)
          put("outcome", call.outcome)
          // Last, and in that order, because they are the two long ones and they read as the call: this is
          // what went out, and this is what came back. Null on both for a session recorded by a build older
          // than they are.
          put("input", call.input)
          put("output", call.output)
        }
      }
    }
  }

  /**
   * Every verdict set by hand, so that an agent arriving at a window someone has been working in reads the
   * conclusions already reached rather than starting over on top of them.
   */
  fun verdicts(overrides: VerdictOverrides): JsonArray = buildJsonArray {
    overrides.all.sortedBy { it.objectId }.forEach { override ->
      addJsonObject {
        put("object", exactHexObjectId(override.objectId))
        put("verdict", override.verdict.name)
        put("why", override.reason)
      }
    }
  }

  /** One object: what it is, how firmly it is held, what it retains, and every field of it. */
  fun objectSummary(
    summary: HeapObjectSummary,
    dominator: ObjectDominator?
  ): JsonObject = buildJsonObject {
    put("object", exactHexObjectId(summary.objectId))
    put("label", summary.label)
    put("className", summary.className)
    put("kind", summary.kind?.name)
    objectContentInto(summary.content)
    put("strength", summary.strength.name)
    put("shallowBytes", summary.shallowSize)
    put("retainedBytes", summary.retainedSize)
    put("retainedObjects", summary.retainedCount)
    put("dominatedObjects", summary.dominatedObjectCount)
    put("verdict", summary.verdict.name)
    put("verdictReason", summary.verdictReason)
    putJsonArray("inspectorLabels") { summary.inspectorLabels.forEach { add(it) } }
    // The one object releasing which would free this one, which is the answer to "what would fix this".
    if (dominator != null) {
      putJsonObject("dominator") {
        put("node", exactHexObjectId(dominator.nodeId))
        put("label", dominator.label)
        put("kind", dominator.kind.name)
        put("retainedBytes", dominator.retainedSize)
      }
    }
    putJsonArray("fields") {
      summary.fields.forEach { field ->
        addJsonObject {
          put("name", field.name)
          put("declaringClass", field.declaringClassName)
          put("value", field.value)
          put("valueObject", field.inspectableObjectId?.let { exactHexObjectId(it) })
        }
      }
    }
    // Only an array reaches this, and an agent that could not see it would read a 10,000 element array as
    // the handful of elements it was shown.
    put("hiddenFieldCount", summary.hiddenFieldCount)
  }

  /**
   * The dominator tree in outline: what holds the most memory, and what holds the most of that.
   *
   * The treemap, without pixels. Which is the answer to "where has the memory gone" rather than to "why is
   * this object still here" — an agent that starts from a leak never needs this, and one asked why an app is
   * using 400 MB has nowhere else to start.
   */
  fun dominatorOutline(outline: DominatorOutline): JsonObject = buildJsonObject {
    put("node", exactHexObjectId(outline.nodeId))
    put("label", outline.label)
    put("retainedBytes", outline.retainedSize)
    put("strength", outline.strength.name)
    // A pile of objects rather than one of them, which is what the top of every tree is mostly made of.
    put("objectCount", outline.objectCount)
    put("className", outline.className)
    // Against the children handed back, so that "this is all of it" and "this is the biggest few of it" are
    // never the same answer.
    put("dominatedNodeCount", outline.childCount)
    putJsonArray("dominates") { outline.children.forEach { add(dominatorOutline(it)) } }
  }

  /** Every device `adb` is connected to, and whether a heap dump could be taken off each. */
  fun devices(devices: List<AndroidDevice>): JsonArray = buildJsonArray {
    devices.forEach { device ->
      addJsonObject {
        put("device", device.serialNumber)
        put("description", device.description)
        put("state", device.state)
        put("sdkInt", device.sdkInt)
        put("model", device.model)
        put("fingerprint", device.fingerprint)
        // The difference between a device with two dumpable processes and one with all of them, and not a
        // question about the app: a release build on a `userdebug` device can be dumped.
        put("dumpsAnyProcess", device.dumpsAnyProcess)
      }
    }
  }

  /** The processes of one device that belong to an installed app. */
  fun processes(processes: List<DeviceProcess>): JsonArray = buildJsonArray {
    processes.forEach { process ->
      addJsonObject {
        put("process", process.name)
        put("processId", process.processId)
        // The system's own apps are dumpable only on a debuggable build, and are thirty of these.
        put("isSystemApp", process.isSystemApp)
      }
    }
  }

  /**
   * One path from a GC root down to an object as a leak trace, with dominators and the suspect references
   * marked — the same trace [humanLeakTrace] renders for a person, in the fields a program reads.
   *
   * **Two renderings of one path and neither is a summary of the other.** This carries everything the
   * window has about each object of it — the labels the inspectors wrote, the verdict and the reason under
   * it, the sizes, the reference it holds the next object through — and the text form carries what
   * `shark.LeakTrace` prints, which is the artefact to show a person and to compare against a LeakCanary
   * report. So a tool that answers with a path answers with both, and
   * `shark/shark-dive/shark-dive-agent/AGENTS.md` has why neither is left to be derived from the other.
   *
   * **A path is objects, and each of them carries the reference it holds the next one through.** Which is
   * the direction a leak trace is printed in and the direction a reader walks it: `Foo instance`, then
   * `↓ Foo.bar`, then what that field points at. The references used to be on the object each one *reaches*
   * instead, which put the whole path's references one object too low and left the GC rooted object at the
   * top as the one entry with no reference at all — an asymmetry that reads as a bug in the walk rather
   * than as a choice. The last object has no reference now, which is the truth: nothing below it is on the
   * path.
   */
  fun leakTrace(path: RootPath): JsonObject = buildJsonObject {
    // Shark's own name for the kind of root rather than `RootPath.gcRootLabel`, which is the window's
    // label for it and reads "GC root: loaded class" — two words this field already says. Null for
    // uncollected garbage, which no GC root reaches, and then the first object's `strength` is UNREACHABLE.
    put("gcRootType", path.gcRootType?.name)
    put("objectCount", path.steps.size)
    val suspects = path.steps.map { it.step }.suspectReferenceIndexes().toSet()
    putJsonArray("path") {
      path.steps.forEachIndexed { index, step ->
        val below = index + 1
        add(rootPathStep(step, path.steps.getOrNull(below)?.step?.reference, below in suspects))
      }
    }
  }

  /**
   * How far the verdicts on a path have got towards naming the one reference the leak is.
   *
   * `leakSolved` first, because it is the one field this surface is worked towards and the answer is read
   * from the top, and `faultyReference` beside it because that is what being solved means.
   *
   * **Nothing here lists the path's own references or objects, and that is deliberate.** The candidates are
   * `isSuspect` on each reference of the path and the objects left to settle are the ones whose `verdict` is
   * `UNKNOWN` between the two ends, so a list of either here would be the path said a second way in the
   * same answer — and two spellings of one fact are two things to keep in step. [PathInvestigation.next]
   * names the objects it is telling a reader to go and settle, which is a sentence rather than a field.
   */
  fun investigation(investigation: PathInvestigation): JsonObject = buildJsonObject {
    put("leakSolved", investigation.leakSolved)
    put("state", investigation.state.name)
    // The reference the path says the leak is, and absent rather than null while the verdicts have not
    // narrowed that far: `leakSolved` is the field that answers whether it is there, and a `null` under a
    // name like this reads as an answer about the reference instead.
    investigation.faultyReference?.let { faulty ->
      putJsonObject("faultyReference") {
        // In the words the leaks screen names the same leak with, so that an answer handed to a person
        // matches the row they are reading it under. See `shark.dive.leakLabel`.
        put("reference", faulty.reference.leakLabel())
        // And where it is on the path, which is the pointer to follow rather than a string to search for:
        // `path[objectIndex].reference` is this reference, on the object that holds it.
        put("objectIndex", faulty.objectIndex)
      }
    }
    put("suspectReferenceCount", investigation.suspectReferenceCount)
    // Rounded, because the digits past the second are a difference no reader acts on and a number that
    // changes in the fifth decimal reads as progress where there was none.
    put("leakSolvingProgressRatio", roundedRatio(investigation.progressRatio))
    put("next", investigation.next)
  }

  /** Every way an object is held, which is what a single path cannot say. */
  fun independentPaths(paths: IndependentPaths): JsonObject = buildJsonObject {
    put("pathCount", paths.paths.size)
    // The search is greedy, so this is the difference between "held these ways" and "held at least these
    // ways" — and an agent concluding that one reference is all that holds an object needs to know which of
    // the two it was told.
    put("hasMore", paths.hasMore)
    putJsonArray("paths") {
      paths.paths.forEach { path ->
        addJsonObject {
          put("gcRootType", path.gcRootType?.name)
          val suspects = path.steps.suspectReferenceIndexes().toSet()
          // The object the search started from is not on this path — it is the GC root, or the `from` the
          // caller named — so the reference out of it has no object here to sit on, and it hangs off the
          // path itself. Null for a path walked from the GC roots, whose first object a root holds through
          // no field. The same rule as everywhere else: whatever holds a reference carries it.
          path.steps.firstOrNull()?.reference?.let { put("reference", pathReference(it, 0 in suspects)) }
          putJsonArray("path") {
            path.steps.forEachIndexed { index, step ->
              val below = index + 1
              add(pathStep(step, path.steps.getOrNull(below)?.reference, below in suspects))
            }
          }
        }
      }
    }
  }

  /**
   * Everything pointing at one object, a page at a time. It is the first step of what [independentPaths]
   * walks to the GC roots, taken over every reference, where a path only takes the ones the dominator tree
   * follows.
   *
   * An object pointing at it is a row of [objectList] with the references it points through. A GC root on it
   * is its type and whether it holds the object. The counts are of every referrer, whichever page this is.
   */
  fun referrers(referrers: ObjectReferrers): JsonObject = buildJsonObject {
    put("referrerCount", referrers.referrerCount)
    // So that "is this the only thing keeping it in memory?" is answered by a count. A running activity has
    // hundreds of objects pointing at it and one that holds it, and counting that off a list is error prone.
    put("holdingReferrerCount", referrers.holdingReferrerCount)
    // The offset to ask for next, so that going on is a number to hand back rather than one to work out.
    put("nextOffset", referrers.nextOffset)
    putJsonArray("referrers") {
      referrers.referrers.forEach { referrer ->
        addJsonObject {
          when (referrer) {
            is GcRootReferrer -> {
              put("gcRootType", referrer.gcRootType.name)
              put("holds", referrer.holds)
            }
            is ObjectReferrer -> {
              objectListEntryInto(referrer.entry)
              putJsonArray("references") {
                referrer.references.forEach { (reference, holds) ->
                  addJsonObject { referenceInto(reference) { put("holds", holds) } }
                }
              }
            }
          }
        }
      }
    }
  }

  /**
   * The leaks screen: what is stuck in this dump, gathered the way the window gathers it.
   *
   * **Field for field what that screen shows**, which is a rule and not a coincidence: the person watching
   * and the agent working are reading one list, and a leak that reads as one thing on the screen and another
   * in the answer is a conversation where neither of them can point at anything. So a leak is its [name] —
   * both ends of the suspect path, exactly as the row draws it — and the references between them are on the
   * path for both readers rather than spelled out for one of them.
   */
  fun leaks(leaks: HeapLeaks): JsonObject = buildJsonObject {
    put("objectCount", leaks.objectCount)
    put("leakingObjectCount", leaks.leakingObjectCount)
    putJsonArray("sections") {
      leaks.sections.forEach { section ->
        addJsonObject {
          put("kind", section.kind.name)
          put("title", section.kind.title)
          // What being in this section means. LeakKind splits that between two fields for the window —
          // `explanation` behind a `?`, and `subtitle` under a group that has no reference to name it with,
          // which is the unreachable section and nothing else — and a JSON answer has one reader and no `?`
          // to click, so whichever of the two a kind fills is the explanation here. Absent for the four
          // sections that fill neither, whose title is the whole of what they are.
          (section.kind.explanation ?: section.kind.subtitle)?.let { put("explanation", it) }
          // Whether this is a leak to investigate or an object the collector will take on its own, which is
          // the split that makes the list actionable. See LeakKind.isOnTheWayOut.
          put("isOnTheWayOut", section.kind.isOnTheWayOut)
          put("objectCount", section.objectCount)
          putJsonArray("groups") {
            section.groups.forEach { group ->
              addJsonObject {
                put("leakFingerprint", group.leakFingerprint)
                // What the leak is, in the words the row of the leaks screen is drawn with: the reference to
                // stop holding, then the one the stuck objects hang off. See [LeakGroup.name].
                put("name", group.name)
                // What is known about a library leak's pattern, which is the one thing a group carries
                // that its references don't say. Only here, because LeakGroup.subtitle is this for a
                // library leak and the section's own explanation for an unreachable one, and a field
                // holding either depending on which section it is under is a field a reader has to work
                // out the meaning of before using it.
                if (section.kind == LeakKind.LIBRARY) {
                  put("libraryLeakDescription", group.subtitle)
                }
                // Addresses and no paths, which is what makes this a list to read once: a path per group
                // came back three and a half times longer on a list of three leaks, and the whole of what
                // it bought was saving one `path_from_gc_roots` on the one group a reader goes on to work.
                // So the list says which leaks there are and what to ask about next, and the path is that
                // question. Any of these addresses is the one to ask it about — see [LeakGroup.objects].
                putJsonArray("objects") {
                  group.objects.forEach { leaking ->
                    addJsonObject {
                      put("object", exactHexObjectId(leaking.objectId))
                      put("className", leaking.className)
                      put("kind", leaking.kind.name)
                      objectContentInto(leaking.content)
                      put("retainedBytes", leaking.retainedSize)
                      put("retainedObjects", leaking.retainedCount)
                      put("strength", leaking.strength.name)
                      put("verdictReason", leaking.verdictReason)
                      // The strongest evidence a heap dump carries: the app itself said this object
                      // should be gone. See WatchedObject.
                      val watcher = leaking.watcher
                      if (watcher != null) {
                        putJsonObject("watchedBecause") {
                          // The `KeyedWeakReference` itself, because the row this answers with is a link on
                          // the screen: it is the leak seen from the watcher's side, and an agent that can
                          // read every other object of the dump should be able to open this one.
                          put("object", exactHexObjectId(watcher.weakReferenceObjectId))
                          put("key", watcher.key)
                          put("description", watcher.description)
                          // How long before the dump the app handed it over, which the row says too. Null in
                          // heap dumps written before LeakCanary 2.0 alpha 3.
                          put("handedOverMillis", watcher.watchDurationMillis)
                          // Whether it survived a collection, which is what makes it a leak rather than a
                          // watcher holding a reference that had already been cleared.
                          put("isRetained", watcher.isRetained)
                          put("retainedMillis", watcher.retainedDurationMillis?.takeIf { watcher.isRetained })
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  /** A filtered list of the dump's objects, with what the filter matched before the cap. */
  fun objectList(list: ObjectList): JsonObject = buildJsonObject {
    put("matchCount", list.matchCount)
    put("totalCount", list.totalCount)
    // So that an agent asking "is this really a singleton?" is answered by the match count rather than by
    // however many rows fitted.
    put("isComplete", !list.hasMore)
    putJsonArray("objects") {
      list.entries.forEach { entry -> addJsonObject { objectListEntryInto(entry) } }
    }
  }

  /** One row of a list of objects, which is also what a referrer is. See [referrers]. */
  private fun JsonObjectBuilder.objectListEntryInto(entry: ObjectListEntry) {
    put("object", exactHexObjectId(entry.objectId))
    put("className", entry.className)
    put("kind", entry.kind.name)
    objectContentInto(entry.content)
    put("shallowBytes", entry.shallowSize)
    put("retainedBytes", entry.retainedSize)
    put("strength", entry.strength.name)
  }

  private fun rootPathStep(
    step: RootPathStep,
    reference: PathReference?,
    isSuspect: Boolean
  ): JsonObject = buildJsonObject {
    pathStepInto(step.step, reference, isSuspect)
    // Every path from a GC root goes through each of an object's dominators, so a marked object is one that
    // releasing would free the object the path leads to, and the rest are only on the way to it.
    put("isDominator", step.isDominator)
  }

  private fun pathStep(
    step: PathStep,
    reference: PathReference?,
    isSuspect: Boolean
  ): JsonObject = buildJsonObject { pathStepInto(step, reference, isSuspect) }

  private fun JsonObjectBuilder.pathStepInto(
    step: PathStep,
    /** The reference this object holds the next one through. Null for the object a path ends at. */
    reference: PathReference?,
    /** Whether [reference] is one the leak could still be. See [shark.dive.suspectReferenceIndexes]. */
    isSuspect: Boolean
  ) {
    put("object", exactHexObjectId(step.objectId))
    put("className", step.className)
    put("kind", step.kind.name)
    objectContentInto(step.content)
    put("strength", step.strength.name)
    put("retainedBytes", step.retainedSize)
    put("retainedObjects", step.retainedCount)
    putJsonArray("inspectorLabels") { step.inspectorLabels.forEach { add(it) } }
    put("verdict", step.verdict.name)
    put("verdictReason", step.verdictReason)
    // PathStep.isTreeNode is not here, and cannot be false on anything an agent reads: both path walks
    // refuse a target the tree has no node for, and an object whose bytes are folded into another one has
    // no incoming reference for a walk to arrive by either. It is the window's field — whether the map has
    // a rectangle to open — and on a path it is a word that always says the same thing, repeated on every
    // object of every path, and again on the path set_verdict reads back after each verdict.
    if (reference != null) {
      put("reference", pathReference(reference, isSuspect))
    }
  }

  /** One reference of a path, on the object that holds it. See [leakTrace]. */
  private fun pathReference(
    reference: PathReference,
    isSuspect: Boolean
  ): JsonObject = buildJsonObject {
    referenceInto(reference) {
      // Whether the leak could still be this reference, which is what a path says about each of them while
      // an investigation runs: true for the stretch the verdicts have not ruled out, and true of a single
      // reference once they have, which is the one to go and change. `faultyReference` on the investigation
      // is that last case named, and `leakSolved` is how to tell the two apart without counting these.
      // PathReference.isFaulty is not here: it is this field in the one state where exactly one of them is
      // left, so a reference carrying both would be the same fact under two names on every path.
      put("isSuspect", isSuspect)
    }
  }

  /**
   * A reference as every answer here spells one, with what the answer it is on says about it after the
   * three fields naming it: [pathReference]'s suspect, or [referrers]' `holds`.
   */
  private fun JsonObjectBuilder.referenceInto(
    reference: PathReference,
    judgement: JsonObjectBuilder.() -> Unit
  ) {
    put("name", reference.name)
    put("ownerClassName", reference.ownerClassName)
    put("locationType", reference.locationType.name)
    judgement()
    val libraryLeak = reference.libraryLeak
    if (libraryLeak != null) {
      putJsonObject("libraryLeak") {
        put("pattern", libraryLeak.pattern)
        put("description", libraryLeak.description)
      }
    }
  }

  /**
   * What an object is beyond its class, as one field per fact it carries and nothing at all for the kinds
   * that carry none.
   *
   * **A field named after what it holds, rather than the line a window draws.** This was one `headline`
   * string — `"420 × 467 pixels, recycled"`, `"42 elements"` — which is a sentence formatted for a screen
   * on a surface read by a program: a width, a height and a boolean arrive as characters to parse back out,
   * and every object that is none of these kinds arrives as a `headline` of null, on every object of every
   * path. So the model carries the facts now ([ObjectContent]), the window formats the sentence, and these
   * are the facts. Absent rather than null, since a String has no bitmap dimensions to report as missing.
   */
  private fun JsonObjectBuilder.objectContentInto(content: ObjectContent?) {
    when (content) {
      null -> Unit
      is ObjectContent.JavaString -> put("stringValue", content.value)
      is ObjectContent.Bitmap -> {
        put("bitmapWidth", content.width)
        put("bitmapHeight", content.height)
        put("bitmapIsRecycled", content.isRecycled)
      }
      is ObjectContent.Thread -> put("threadName", content.name)
      is ObjectContent.ObjectArray -> put("arrayElementCount", content.elementCount)
      is ObjectContent.PrimitiveArray -> put("arrayByteCount", content.byteCount)
    }
  }

  /**
   * The same path as the leak trace LeakCanary prints, which is the rendering of it meant for a person.
   *
   * **The whole point of handing this over is that nobody else assembles one.** [leakTrace] beside it
   * describes the same path field by field, so a model has everything it needs to type a leak trace of its
   * own — and one typed out is a retelling: a step dropped, the underline moved, a class name shortened,
   * each of which is invisible to whoever reads the result and none of which can happen to these
   * characters. So the tool renders it, in `shark.LeakTrace`'s own words, and the instruction on the tools
   * that carry it is to quote it unchanged.
   *
   * Newlines and all, as a JSON string: `shark.LeakTrace.toString` is many lines, and what goes over the
   * wire is one `\n`-escaped string that any JSON reader hands back as the lines it was.
   *
   * Null for a path with no steps, which is nothing to render. See [shark.dive.leakTrace].
   */
  fun humanLeakTrace(path: RootPath): String? = path.leakTrace()?.toString()

  /**
   * A ratio as every answer carries one: still 0 to 1, rounded to two decimals.
   *
   * Rounded because the digits past the second are a difference nobody acts on, and a figure that moves in
   * the fifth decimal reads as progress where there was none. One function so that the pair `set_verdict`
   * answers with — before and after — is rounded the same way at both ends, which is what makes comparing
   * them mean anything.
   *
   * **This multiplies by a hundred and divides by it again, and that is two decimal places and not a
   * conversion to percent** — which is a sentence worth having here because somebody read it as one. The
   * name of the constant is half of what makes that readable and the `Ratio` on everything carrying the
   * number out of here is the other half; [shark.dive.leakSolvingProgressRatio] has why.
   */
  fun roundedRatio(ratio: Double): Double = round(ratio * TWO_DECIMAL_PLACES) / TWO_DECIMAL_PLACES

  private const val TWO_DECIMAL_PLACES = 100.0
}
