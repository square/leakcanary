package shark.internal

import shark.GcRoot.ThreadObject
import shark.HeapGraph
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.IgnoredReferenceMatcher
import shark.OnAnalysisProgressListener
import shark.ValueHolder

/**
 * Exposes high level APIs to compute and render a dominator tree. This class
 * needs to be public to be used by other LeakCanary modules but is internal and
 * its API might change at any moment.
 *
 * Note that the exposed APIs are not optimized for speed, memory or IO.
 *
 * Eventually this capability should become part of the Shark public APIs, please
 * open an issue if you'd like to use this directly.
 */
class ObjectDominators {

  internal data class DominatorNode(
    val shallowSize: Int,
    val retainedSize: Int,
    val retainedCount: Int,
    val dominatedObjectIds: List<Long>
  )

  fun renderDominatorTree(
    graph: HeapGraph,
    ignoredRefs: List<IgnoredReferenceMatcher>,
    minRetainedSize: Int,
    threadName: String? = null,
    printStringContent: Boolean = false
  ): String {
    val stringBuilder = StringBuilder()

    val dominatorTree = buildDominatorTree(graph, ignoredRefs)

    val root = dominatorTree.getValue(ValueHolder.NULL_REFERENCE)
    stringBuilder.append(
        "Total retained: ${root.retainedSize} bytes in ${root.retainedCount} objects. Root dominators: ${root.dominatedObjectIds.size}\n\n"
    )

    val rootIds = if (threadName != null) {
      setOf(graph.gcRoots.first { gcRoot ->
        gcRoot is ThreadObject &&
            graph.objectExists(gcRoot.id) &&
            graph.findObjectById(gcRoot.id)
                .asInstance!!["java.lang.Thread", "name"]!!
                .value.readAsJavaString() == threadName
      }.id)
    } else {
      root.dominatedObjectIds.filter { dominatorTree.getValue(it).retainedSize > minRetainedSize }
    }

    rootIds
        .forEach { objectId ->
          printTree(
              stringBuilder, graph, dominatorTree, objectId, minRetainedSize, 0, "", true,
              printStringContent
          )
          stringBuilder.append("\n")
        }
    return stringBuilder.toString()
  }

  @Suppress("LongParameterList")
  private fun printTree(
    stringBuilder: StringBuilder,
    graph: HeapGraph,
    tree: Map<Long, DominatorNode>,
    objectId: Long,
    minSize: Int,
    depth: Int,
    prefix: String,
    isLast: Boolean,
    printStringContent: Boolean
  ) {
    val node = tree.getValue(objectId)
    val heapObject = graph.findObjectById(objectId)
    val className = when (heapObject) {
      is HeapClass -> "class ${heapObject.name}"
      is HeapInstance -> heapObject.instanceClassName
      is HeapObjectArray -> heapObject.arrayClassName
      is HeapPrimitiveArray -> heapObject.arrayClassName
    }
    val anchor = if (depth == 0) "" else if (isLast) "╰─" else "├─"
    val size = if (node.retainedSize != node.shallowSize) {
      "${node.retainedSize} bytes (${node.shallowSize} self)"
    } else {
      "${node.shallowSize} bytes"
    }
    val count = if (node.retainedCount > 1) {
      " ${node.retainedCount} objects"
    } else {
      ""
    }
    val stringContent = if (
        printStringContent &&
        heapObject is HeapInstance &&
            heapObject.instanceClassName == "java.lang.String"
    ) " \"${heapObject.readAsJavaString()}\"" else ""
    stringBuilder.append(
        "$prefix$anchor$className #${heapObject.objectIndex} Retained: $size$count$stringContent\n"
    )

    val newPrefix = when {
      depth == 0 -> ""
      isLast -> {
        "$prefix  "
      }
      else -> {
        "$prefix│ "
      }
    }

    val largeChildren = node.dominatedObjectIds.filter { tree.getValue(it).retainedSize > minSize }
    val lastIndex = node.dominatedObjectIds.lastIndex

    largeChildren.forEachIndexed { index, objectId ->
      printTree(
          stringBuilder,
          graph, tree, objectId, minSize, depth + 1, newPrefix,
          index == lastIndex,
          printStringContent
      )
    }
    if (largeChildren.size < node.dominatedObjectIds.size) {
      stringBuilder.append("$newPrefix╰┄\n")
    }
  }

  private fun buildDominatorTree(
    graph: HeapGraph,
    ignoredRefs: List<IgnoredReferenceMatcher>
  ): Map<Long, DominatorNode> {
    val pathFinder = PathFinder(graph,
        OnAnalysisProgressListener.NO_OP, ignoredRefs)
    val nativeSizeMapper = AndroidNativeSizeMapper(graph)
    val nativeSizes = nativeSizeMapper.mapNativeSizes()
    val shallowSizeCalculator = ShallowSizeCalculator(graph)

    val result = pathFinder.findPathsFromGcRoots(setOf(), true)
    return result.dominatorTree!!.buildFullDominatorTree { objectId ->
      val nativeSize = nativeSizes[objectId] ?: 0
      val shallowSize = shallowSizeCalculator.computeShallowSize(objectId)
      nativeSize + shallowSize
    }
  }
}
