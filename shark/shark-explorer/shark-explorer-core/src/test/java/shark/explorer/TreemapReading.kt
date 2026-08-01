package shark.explorer

/** How the tests of a [HeapDominatorTreemap] read one, shared by the classes that assert on trees. */

internal fun HeapDominatorTreemap.findByLabel(label: String): HeapObjectSummary =
  allSummaries().single { it.label == label }

/** Every object of the tree, walked past the groups, which stand for objects rather than being one. */
internal fun HeapDominatorTreemap.allSummaries(): List<HeapObjectSummary> {
  val summaries = mutableListOf<HeapObjectSummary>()
  val toVisit = ArrayDeque(listOf(root))
  while (toVisit.isNotEmpty()) {
    val node = toVisit.removeFirst()
    if (groupOrNull(node) == null) {
      summaries += summarize(node)
    }
    toVisit += children(node)
  }
  return summaries
}

/**
 * Every way an object is held below whatever the tree says holds it, which is the question these tests ask
 * of the search: the same two ends the window asks it of when the stretch of a chain in doubt is the one
 * running down from a dominator.
 *
 * A group holds an object from the roots the tree was walked from rather than from an object of the heap
 * dump, so which of the two searches answers depends on which kind of thing dominates it.
 */
internal fun HeapDominatorTreemap.independentPathsBelowDominator(objectId: Long): IndependentPaths {
  val dominator = dominatorOf(objectId) ?: return IndependentPaths.NONE
  return if (dominator.kind == DominatorKind.OBJECT) {
    independentPathsBetween(dominator.nodeId, objectId)
  } else {
    independentPathsFromRoots(objectId)
  }
}

/**
 * How these tests read a path: the field each step was reached through, then what it points at. The
 * first step of a path below a group is the GC root's own object, which no field points at.
 */
internal fun IndependentPath.stepLabels(): List<String> = steps.map { it.label() }

/** The same, for the one chain leading down from a GC root. */
internal fun RootPath.stepLabels(): List<String> = steps.map { it.step.label() }

private fun PathStep.label(): String {
  val simpleClassName = className.substringAfterLast('.')
  return reference?.let { "${it.name} → $simpleClassName" } ?: simpleClassName
}
