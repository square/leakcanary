package shark.explorer.agent

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
import shark.explorer.AndroidDevice
import shark.explorer.DeviceProcess
import shark.explorer.DominatorOutline
import shark.explorer.HeapLeaks
import shark.explorer.HeapObjectSummary
import shark.explorer.HeapSizes
import shark.explorer.IndependentPaths
import shark.explorer.LeakStatusOverrides
import shark.explorer.ObjectDominator
import shark.explorer.ObjectList
import shark.explorer.PathStep
import shark.explorer.ReachabilityStrength
import shark.explorer.RootPath
import shark.explorer.RootPathStep
import shark.explorer.exactHexObjectId
import shark.explorer.faultyReference
import shark.explorer.leakLabel

/**
 * How the explorer's own model reads as JSON, which is the whole of what an agent sees of a heap dump.
 *
 * Two rules run through all of it, and both are about being read by something that is not this app.
 *
 * **An address is a string, never a number.** A heap dump's addresses fill the whole range of `Long`, and a
 * JSON number is a double to most clients of this protocol — anything above 2^53 comes back rounded, which
 * for an address means a different object, silently. So every one of them is [exactHexObjectId], the same
 * spelling this app's own files use, and [shark.explorer.objectIdOfHex] is the only way back.
 *
 * **Nothing is summarised away, and every cap says so.** A field an agent can't see is a field it will
 * guess at, so the answers here are what the window shows: the labels the inspectors wrote, the verdict and
 * the reason under it, the whole chain. Where an answer is capped — a list of objects, the ways one is held
 * — the count that was matched and whether anything was left out go with it, because an agent counting the
 * instances of a class must never be counting the page it was shown.
 */
internal object AgentJson {

  /** Which window, which dump, how big it is, and what has been concluded about it so far. */
  fun heapDump(
    windowId: String,
    heapDumpPath: String,
    sizes: HeapSizes,
    verdicts: LeakStatusOverrides
  ): JsonObject = buildJsonObject {
    put("window", windowId)
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
  }

  /**
   * Every verdict set by hand, so that an agent arriving at a window someone has been working in reads the
   * conclusions already reached rather than starting over on top of them.
   */
  fun verdicts(overrides: LeakStatusOverrides): JsonArray = buildJsonArray {
    overrides.all.sortedBy { it.objectId }.forEach { override ->
      addJsonObject {
        put("object", exactHexObjectId(override.objectId))
        put("verdict", override.status.name)
        put("reason", override.reason)
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
    put("headline", summary.headline)
    put("strength", summary.strength.name)
    put("shallowBytes", summary.shallowSize)
    put("retainedBytes", summary.retainedSize)
    put("retainedObjects", summary.retainedCount)
    put("dominatedObjects", summary.dominatedObjectCount)
    put("verdict", summary.leakStatus.name)
    put("verdictReason", summary.leakStatusReason)
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

  /** The shortest chain from a GC root down to an object, with dominators and the faulty reference marked. */
  fun rootPath(path: RootPath): JsonObject = buildJsonObject {
    put("gcRoot", path.gcRootLabel)
    put("stepCount", path.steps.size)
    // What the chain is for, said once at the top rather than left to be found by scanning the steps for
    // isFaulty — and in the words the window names the leak with, so that an answer handed to a person
    // matches the section they are reading it under. Null until the verdicts either side of one reference
    // are both set, which is the state an investigation is working towards.
    put("faultyReference", path.faultyReference()?.leakLabel())
    putJsonArray("steps") { path.steps.forEach { add(rootPathStep(it)) } }
  }

  /** Every way an object is held, which is what a single chain cannot say. */
  fun independentPaths(paths: IndependentPaths): JsonObject = buildJsonObject {
    put("pathCount", paths.paths.size)
    // The search is greedy, so this is the difference between "held these ways" and "held at least these
    // ways" — and an agent concluding that one reference is all that holds an object needs to know which of
    // the two it was told.
    put("hasMore", paths.hasMore)
    putJsonArray("paths") {
      paths.paths.forEach { path ->
        addJsonObject {
          put("gcRoot", path.gcRootLabel)
          putJsonArray("steps") { path.steps.forEach { add(pathStep(it)) } }
        }
      }
    }
  }

  /** The leaks screen: what is stuck in this dump, gathered the way the window gathers it. */
  fun leaks(leaks: HeapLeaks): JsonObject = buildJsonObject {
    put("objectCount", leaks.objectCount)
    put("leakingObjectCount", leaks.leakingObjectCount)
    putJsonArray("sections") {
      leaks.sections.forEach { section ->
        addJsonObject {
          put("kind", section.kind.name)
          put("title", section.kind.title)
          put("explanation", section.kind.explanation)
          // Whether this is a leak to fix or an object the collector will take on its own, which is the
          // split that makes the list actionable. See LeakKind.isOnTheWayOut.
          put("isOnTheWayOut", section.kind.isOnTheWayOut)
          put("objectCount", section.objectCount)
          putJsonArray("groups") {
            section.groups.forEach { group ->
              addJsonObject {
                put("leakFingerprint", group.leakFingerprint)
                put("title", group.title)
                put("subtitle", group.subtitle)
                // The references the leak *is*, which is what a leak investigation ends at and therefore
                // the thing an agent must not have to reconstruct from a chain.
                putJsonArray("suspectPath") { group.suspectPath.forEach { add(it) } }
                put("retainedBytes", group.retainedSize)
                putJsonArray("objects") {
                  group.objects.forEach { leaking ->
                    addJsonObject {
                      put("object", exactHexObjectId(leaking.objectId))
                      put("className", leaking.className)
                      put("kind", leaking.kind.name)
                      put("headline", leaking.headline)
                      put("retainedBytes", leaking.retainedSize)
                      put("retainedObjects", leaking.retainedCount)
                      put("strength", leaking.strength.name)
                      put("leakingReason", leaking.leakingReason)
                      // The strongest evidence a heap dump carries: the app itself said this object
                      // should be gone. See WatchedObject.
                      val watcher = leaking.watcher
                      if (watcher != null) {
                        putJsonObject("watchedBecause") {
                          put("key", watcher.key)
                          put("description", watcher.description)
                          put("retainedMillis", watcher.retainedDurationMillis)
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
      list.entries.forEach { entry ->
        addJsonObject {
          put("object", exactHexObjectId(entry.objectId))
          put("className", entry.className)
          put("kind", entry.kind.name)
          put("headline", entry.headline)
          put("shallowBytes", entry.shallowSize)
          put("retainedBytes", entry.retainedSize)
          put("strength", entry.strength.name)
        }
      }
    }
  }

  private fun rootPathStep(step: RootPathStep): JsonObject = buildJsonObject {
    pathStepInto(step.step)
    // Every path from a GC root goes through each of an object's dominators, so a marked step is one that
    // releasing would free the object and the rest are only on the way to it.
    put("isDominator", step.isDominator)
  }

  private fun pathStep(step: PathStep): JsonObject = buildJsonObject { pathStepInto(step) }

  private fun JsonObjectBuilder.pathStepInto(step: PathStep) {
    put("object", exactHexObjectId(step.objectId))
    put("className", step.className)
    put("kind", step.kind.name)
    put("headline", step.headline)
    put("strength", step.strength.name)
    put("retainedBytes", step.retainedSize)
    put("retainedObjects", step.retainedCount)
    putJsonArray("inspectorLabels") { step.inspectorLabels.forEach { add(it) } }
    put("verdict", step.leakStatus.name)
    put("verdictReason", step.leakStatusReason)
    put("isInspectable", step.isInspectable)
    val reference = step.reference
    if (reference != null) {
      putJsonObject("reference") {
        put("name", reference.name)
        put("ownerClassName", reference.ownerClassName)
        put("locationType", reference.locationType.name)
        // The one thing on a chain that says where to go and change code.
        put("isFaulty", reference.isFaulty)
        val libraryLeak = reference.libraryLeak
        if (libraryLeak != null) {
          putJsonObject("libraryLeak") {
            put("pattern", libraryLeak.pattern)
            put("description", libraryLeak.description)
          }
        }
      }
    }
  }
}
