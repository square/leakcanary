package shark

import shark.LeakTraceObject.LeakingStatus
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.RealLeakTracerFactory.ShortestPath

/**
 * Holds the result of the initial one-time analysis together with cached shortest paths so that
 * the inspection+bisecting step can be re-run cheaply whenever the user overrides a node's status.
 *
 * @param allLeaks All leaks found, application leaks first then library leaks, in the same order
 *   as [groupedPaths].
 * @param groupedPaths Outer list is parallel to [allLeaks]. Inner list is parallel to
 *   [Leak.leakTraces]. Each [CachedPath] holds the corresponding [ShortestPath] plus an ordered
 *   list of object IDs so that a trace-node index maps to a concrete heap object.
 */
class InitialAnalysis internal constructor(
  val allLeaks: List<Leak>,
  val groupedPaths: List<List<CachedPath>>
)

/**
 * A cached reference path produced during the BFS phase of the initial analysis.
 *
 * @param objectIds Ordered list of heap object IDs corresponding to the nodes in the associated
 *   [LeakTrace]. Index 0 is the GC root object; the last index ([LeakTrace.referencePath].size)
 *   is the leaking object, matching [LeakTrace.leakingObject].
 */
class CachedPath internal constructor(
  internal val shortestPath: ShortestPath,
  val objectIds: List<Long>
)

/**
 * Drives an interactive leak investigation session over an open heap dump.
 *
 * ## Design rationale
 *
 * LeakCanary's analysis pipeline has two expensive stages and two cheap ones:
 *
 *  1. **Path finding (BFS)** — traverses the entire heap graph to find shortest reference paths
 *     from GC roots to each retained (leaking) object. This stage takes seconds and dominates
 *     analysis time.
 *  2. **Deduplication** — removes duplicate paths that share the same route through the graph.
 *     Runs in milliseconds.
 *  3. **Inspection + bisecting** — runs [ObjectInspector] implementations on every node in every
 *     found path, then propagates `LEAKING`/`NOT_LEAKING` statuses via `computeLeakStatuses()`:
 *     NOT_LEAKING cascades downward toward the leaking object; LEAKING cascades upward toward the
 *     GC root. The "suspect window" — the references an agent should investigate — is the range
 *     between the last NOT_LEAKING node and the first LEAKING node. Runs in milliseconds.
 *  4. **Trace building** — assembles [LeakTrace] data objects from the inspection results.
 *     Milliseconds.
 *
 * [analyze] runs all four stages once and caches the [ShortestPath] objects from stage 1+2 in
 * the returned [InitialAnalysis]. [reinspectPath] re-runs only stages 3+4 for a single path,
 * so the agent's status overrides are reflected immediately without repeating the expensive BFS.
 *
 * ## Override precedence
 *
 * Status overrides are injected as an [ObjectInspector] appended **last** to the standard
 * inspector list. When an override fires it clears the *opposing* reason set on [ObjectReporter]
 * before adding its own reason, eliminating any conflict before `resolveStatus()` is called.
 * Because `resolveStatus()` only sees one non-empty set, the override always wins regardless of
 * what the standard inspectors said — no `leakingWins` flag magic needed.
 *
 * Example: a standard inspector marks a `View` as NOT_LEAKING ("View not attached"). The agent
 * believes the View should be LEAKING. The override inspector clears `notLeakingReasons` and
 * adds to `leakingReasons`. `resolveStatus()` sees only LEAKING → override wins.
 *
 * @param referenceMatchers Matchers guiding BFS traversal and identifying known library leaks.
 *   Typically [AndroidReferenceMatchers.appDefaults].
 * @param objectInspectors Inspectors that label heap objects and determine leaking status.
 *   Typically [AndroidObjectInspectors.appDefaults].
 */
class LeakInvestigationSession(
  private val referenceMatchers: List<ReferenceMatcher>,
  private val objectInspectors: List<ObjectInspector>
) {

  /**
   * Runs the full analysis pipeline (BFS path finding + inspection + bisecting) once and caches
   * the found paths. Call this once per heap dump session.
   *
   * @param graph The open heap graph from the hprof file.
   * @param leakingObjectIds IDs of the retained objects to trace from GC roots.
   * @return [InitialAnalysis] with all found leaks and their cached paths for re-inspection.
   */
  fun analyze(
    graph: HeapGraph,
    leakingObjectIds: Set<Long>
  ): InitialAnalysis {
    val result = buildFactory().findLeaksWithCachedPaths(graph, leakingObjectIds)

    val allLeaks: List<Leak> = result.applicationLeaks + result.libraryLeaks

    val groupedPaths = result.groupedPaths.map { groupPaths ->
      groupPaths.map { shortestPath ->
        CachedPath(shortestPath, extractObjectIds(shortestPath))
      }
    }

    return InitialAnalysis(allLeaks, groupedPaths)
  }

  /**
   * Re-runs only the inspection + bisecting stages (no BFS) for a single [CachedPath], applying
   * [statusOverrides] on top of the standard inspectors.
   *
   * The returned [LeakTrace] can be displayed with [LeakTrace.toString] to produce the standard
   * LeakCanary human-readable output with updated `Leaking: YES/NO/UNKNOWN` annotations and
   * recalculated suspect-window underlines.
   *
   * @param graph The open heap graph (same instance as used for [analyze]).
   * @param cachedPath The path to re-inspect, from [InitialAnalysis.groupedPaths].
   * @param statusOverrides Map of heap object ID to (status, reason). The override inspector
   *   runs last and clears the opposing reason set so overrides always win over inspectors.
   * @return A fresh [LeakTrace] reflecting the current override state.
   */
  fun reinspectPath(
    graph: HeapGraph,
    cachedPath: CachedPath,
    statusOverrides: Map<Long, Pair<LeakingStatus, String>>
  ): LeakTrace {
    val overrideInspector = buildOverrideInspector(statusOverrides)
    return buildFactory().reinspectPath(graph, cachedPath.shortestPath, listOf(overrideInspector))
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private fun buildFactory(): RealLeakTracerFactory {
    return RealLeakTracerFactory(
      shortestPathFinderFactory = PrioritizingShortestPathFinder.Factory(
        listener = { },
        referenceReaderFactory = AndroidReferenceReaderFactory(referenceMatchers),
        gcRootProvider = MatchingGcRootProvider(referenceMatchers),
        computeRetainedHeapSize = false
      ),
      objectInspectors = objectInspectors
    ) { }
  }

  /**
   * Builds the override [ObjectInspector]. It runs **last** in the inspector list so it can
   * unconditionally win by clearing the opposing reason set:
   * - LEAKING override  → clears [ObjectReporter.notLeakingReasons], adds to [ObjectReporter.leakingReasons]
   * - NOT_LEAKING override → clears [ObjectReporter.leakingReasons], adds to [ObjectReporter.notLeakingReasons]
   * - UNKNOWN override → no-op (callers should remove the entry from [statusOverrides] instead)
   */
  private fun buildOverrideInspector(
    statusOverrides: Map<Long, Pair<LeakingStatus, String>>
  ): ObjectInspector = ObjectInspector { reporter ->
    val (status, reason) = statusOverrides[reporter.heapObject.objectId] ?: return@ObjectInspector
    when (status) {
      LEAKING -> {
        reporter.notLeakingReasons.clear()
        reporter.leakingReasons += reason
      }
      NOT_LEAKING -> {
        reporter.leakingReasons.clear()
        reporter.notLeakingReasons += reason
      }
      LeakingStatus.UNKNOWN -> { /* caller removes the entry from the map */ }
    }
  }

  /**
   * Extracts heap object IDs from a [ShortestPath] in trace order:
   * index 0 = GC root object, last index = leaking object.
   */
  private fun extractObjectIds(path: ShortestPath): List<Long> {
    val ids = mutableListOf(path.root.objectId)
    path.childPathWithDetails.mapTo(ids) { (childNode, _) -> childNode.objectId }
    return ids
  }
}
