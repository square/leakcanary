package shark

import androidx.collection.mutableLongSetOf
import com.github.ajalt.clikt.core.CliktCommand
import java.io.File
import java.io.PrintWriter
import java.util.EnumSet
import shark.FilteringLeakingObjectFinder.LeakingObjectFilter
import shark.GcRoot.JavaFrame
import shark.GcRoot.StickyClass
import shark.GcRoot.ThreadObject
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.NodeForDominators.HeapNode
import shark.NodeForDominators.UniqueInstanceNode
import shark.SharkCliCommand.Companion.retrieveHeapDumpFile
import shark.SharkCliCommand.Companion.sharkCliParams

class DominatorTreeCommand : CliktCommand(
  name = "dominators",
  help = "Compute dominators"
) {
  override fun run() {
    val heapDumpFile = retrieveHeapDumpFile(context.sharkCliParams)

    val weakAndFinalizerRefs = EnumSet.of(
      AndroidReferenceMatchers.REFERENCES, AndroidReferenceMatchers.FINALIZER_WATCHDOG_DAEMON
    )
    val ignoredRefs =
      ReferenceMatcher.fromListBuilders(weakAndFinalizerRefs).map { matcher ->
        matcher as IgnoredReferenceMatcher
      }

    val rootOfRoots = heapDumpFile.openHeapGraph().use { graph ->

      val allGcRoots = MatchingGcRootProvider(ignoredRefs).provideGcRoots(graph)

      val reader = ActualMatchingReferenceReaderFactory(
        referenceMatchers = ignoredRefs
      ).createFor(graph)
      // Do a graph traversal and keep track of all paths that have unique roots and output that.

      fun buildPredecessors(gcRoots: Sequence<Pair<Long, String>>): Map<Long, HelperNode> {
        val cache = mutableMapOf<Long, HelperNode>()
        val root = HelperNode.retrieve(cache, null, "", ValueHolder.NULL_REFERENCE, "root")
        val queue = ArrayDeque<HelperNode>()
        val visited = mutableLongSetOf()

        val stickyClassIds = allGcRoots.mapNotNull { (it.gcRoot as? StickyClass)?.id }
        val stickyClassIdsSet = stickyClassIds.toSet()

        // 1) Traverse the whole tree
        queue.addAll(
          gcRoots
            .map {
              HelperNode.retrieve(
                cache = cache,
                parent = root,
                referenceName = "gcRoot",
                objectId = it.first,
                name = it.second
              )
            })
        println("Starting queue size is ${queue.size}")
        while (queue.isNotEmpty()) {
          val dequeued = queue.removeFirst()
          if (dequeued.objectId !in visited) {
            visited += dequeued.objectId
            val source = graph.findObjectById(dequeued.objectId)
            val references = reader.read(source).mapNotNull { ref ->
              val referenced = graph.findObjectById(ref.valueObjectId)
              if (referenced is HeapInstance) {
                if (referenced instanceOf "android.app.Activity" && referenced["android.app.Activity", "mDestroyed"]?.value?.asBoolean == false) {
                  // no reference to live activities is valid, we all move them to ActivityThread
                  return@mapNotNull null
                } else if (referenced instanceOf "android.view.View" && referenced["android.view.View", "mAttachInfo"]!!.value.isNonNullReference) {
                  // TODO Only exclude views tied to an activity context.
                  // This is an attached view. Attached views are only reachable directly through their activity context, for now anyway.
                  return@mapNotNull null
                } else if (referenced instanceOf "android.app.ActivityThread" && source.objectId != referenced.instanceClassId && referenced.instanceClass["sCurrentActivityThread"]!!.value.asObjectId == referenced.objectId) {
                  // The current activity thread can only be referenced by its singleton static field sCurrentActivityThread.
                  return@mapNotNull null
                } else if (referenced instanceOf "java.lang.DexCache" || referenced instanceOf "java.lang.ClassLoader") {
                  return@mapNotNull null
                }
              } else if (referenced is HeapClass && referenced.objectId in stickyClassIdsSet) {
                // No referenced allowed for stick class, we have a GC root node providing those.
                return@mapNotNull null
              }
              ref.valueObjectId to ref.lazyDetailsResolver.resolve().name
            }
            val additionalReferences = if (source is HeapInstance) {
              if (source instanceOf "android.app.Activity" && source["android.app.Activity", "mDestroyed"]?.value?.asBoolean == false) {
                // Find all attached views that have this activity as a base context
                val viewInstances =
                  graph.findClassByName("android.view.View")?.instances ?: emptySequence()
                viewInstances.mapNotNull { viewInstance ->
                  val viewAttached =
                    viewInstance["android.view.View", "mAttachInfo"]!!.value.isNonNullReference
                  if (viewAttached) {
                    val baseContext =
                      viewInstance["android.view.View", "mContext"]!!.valueAsInstance!!.unwrapComponentContext()
                    if (baseContext?.objectId == source.objectId) {
                      return@mapNotNull viewInstance.objectId to "views"
                    }
                  }
                  null
                }
              } else if (source instanceOf "android.app.ActivityThread") {
                graph.findClassByName("android.app.Activity")!!
                  .instances
                  .filter { it["android.app.Activity", "mDestroyed"]?.value?.asBoolean == false }
                  .map { it.objectId to "liveActivities" }
              } else {
                emptySequence()
              }
            } else {
              emptySequence()
            }
            references + additionalReferences

            // #################################

            (references + additionalReferences).forEach { (objectId, name) ->
              queue.add(
                HelperNode.retrieve(
                  cache, dequeued, name, objectId,
                  graph.findObjectById(objectId).toString()
                )
              )
            }
          }
        }
        return cache
      }

      fun traversePredecessors(
        targetNode: HelperNode,
        block: (HelperNode, HelperNode, String) -> Unit
      ) {
        val visitedPredecessors = mutableLongSetOf()
        val predecessorQueue = ArrayDeque<HelperNode>()
        predecessorQueue += targetNode
        while (predecessorQueue.isNotEmpty()) {
          val dequeued = predecessorQueue.removeFirst()

          if (dequeued.objectId !in visitedPredecessors) {
            visitedPredecessors += dequeued.objectId
            dequeued.predecessors.forEach { (node, referenceName) ->
              block(node, dequeued, referenceName)
            }
            predecessorQueue.addAll(dequeued.predecessors.map { it.first })
          }
        }
      }

      val interestingRoots =
        allGcRoots.filter { it.gcRoot !is JavaFrame && it.gcRoot !is ThreadObject }
          .map {
            it.gcRoot.id to "${it.gcRoot::class.java.simpleName}: ${it.gcRoot.id} ${graph.findObjectById(it.gcRoot.id).toString()}"
          }

      val targetObjectId = 329071160L
      val targetNode = buildPredecessors(interestingRoots).getValue(targetObjectId)

      val possibleGcRootDominators = mutableSetOf<HelperNode>()
      traversePredecessors(targetNode) { predecessor, referenced, referenceName ->
        if (referenceName == "gcRoot") {
          possibleGcRootDominators += referenced
        }
      }
      println("Found ${possibleGcRootDominators.size} multi dominators")

      val targetNodeFromMultiDominators = if (true) {
        buildPredecessors(possibleGcRootDominators.map { it.objectId to it.name }.asSequence()).getValue(
          targetObjectId
        )
      } else {
        buildPredecessors(
          sequenceOf(possibleGcRootDominators.first().run { objectId to name })
        ).getValue(
          targetObjectId
        )
      }

      val printedNodes = mutableLongSetOf()
      val dotFile = File(heapDumpFile.parent, "${heapDumpFile.nameWithoutExtension}-target.dot")
      println("Creating ${dotFile.absolutePath}")
      dotFile.printWriter().use { out ->
        out.println("digraph traversal {")
        out.println(
          "  node${targetNodeFromMultiDominators.objectId} [label=\"${targetNodeFromMultiDominators.name}\"]"
        )
        traversePredecessors(
          targetNodeFromMultiDominators
        ) { predecessor, referenced, referenceName ->
          if (predecessor.objectId !in printedNodes) {
            printedNodes += predecessor.objectId
            out.println("  node${predecessor.objectId} [label=\"${predecessor.name}\"];")
          }
          out.println(
            "  node${predecessor.objectId} -> node${referenced.objectId} [label=\"$referenceName\"];"
          )
        }
        out.println("}")
      }

      val ids =
        graph.findClassByName(
          "androidx.compose.ui.graphics.AndroidImageBitmap"
        )!!.instances.map { it.objectId }.toSet()
      println("Found ${ids.size}")

      val heapAnalyzer = HeapAnalyzer {
      }
      val heapAnalysis = heapAnalyzer.analyze(
        heapDumpFile = heapDumpFile,
        graph,
        leakingObjectFinder = FilteringLeakingObjectFinder(listOf(object :
          LeakingObjectFilter {
          override fun isLeakingObject(heapObject: HeapObject): Boolean {
            return heapObject.objectId in ids
          }
        })),
        referenceMatchers = AndroidReferenceMatchers.appDefaults,
        computeRetainedHeapSize = true,
        metadataExtractor = AndroidMetadataExtractor
      )
      println(heapAnalysis)

      val stickyClassIds = allGcRoots.mapNotNull { (it.gcRoot as? StickyClass)?.id }
      val stickyClassIdsSet = stickyClassIds.toSet()

      val gcRootNodesPrevious = allGcRoots
        // Java frames mess with dominators by preventing unique roots
        .filter { it.gcRoot !is JavaFrame }
        .groupBy { it.gcRoot::class.java }
        .entries
        .map { (key, value) ->
          UniqueInstanceNode(
            name = key.simpleName, childNodes = value.map { HeapNode(it.gcRoot.id) }.asSequence()
          ) as NodeForDominators
        }.toSet()

      val gcRootNodes = possibleGcRootDominators.map { node ->
        HeapNode(node.objectId)
      }.toSet<NodeForDominators>()

      val refReader = ActualMatchingReferenceReaderFactory(
        referenceMatchers = ignoredRefs
      ).createFor(graph)

      val (objectIdsInTopologicalOrder, immediateDominators) = LinkEvalDominators.computeDominators(
        gcRootNodes
      ) { node ->
        when (node) {
          is HeapNode -> {
            val source = graph.findObjectById(node.objectId)
            val references = refReader.read(source).mapNotNull { ref ->
              val referenced = graph.findObjectById(ref.valueObjectId)
              if (referenced is HeapInstance) {
                if (referenced instanceOf "android.app.Activity" && referenced["android.app.Activity", "mDestroyed"]?.value?.asBoolean == false) {
                  // no reference to live activities is valid, we all move them to ActivityThread
                  return@mapNotNull null
                } else if (referenced instanceOf "android.view.View" && referenced["android.view.View", "mAttachInfo"]!!.value.isNonNullReference) {
                  // TODO Only exclude views tied to an activity context.
                  // This is an attached view. Attached views are only reachable directly through their activity context, for now anyway.
                  return@mapNotNull null
                } else if (referenced instanceOf "android.app.ActivityThread" && source.objectId != referenced.instanceClassId && referenced.instanceClass["sCurrentActivityThread"]!!.value.asObjectId == referenced.objectId) {
                  // The current activity thread can only be referenced by its singleton static field sCurrentActivityThread.
                  return@mapNotNull null
                } else if (referenced instanceOf "java.lang.DexCache" || referenced instanceOf "java.lang.ClassLoader") {
                  return@mapNotNull null
                }
              } else if (referenced is HeapClass && referenced.objectId in stickyClassIdsSet) {
                // No referenced allowed for stick class, we have a GC root node providing those.
                return@mapNotNull null
              }
              HeapNode(ref.valueObjectId)
            }
            //  if (referenced instanceOf "android.app.ActivityThread") {
            val additionalReferences = if (source is HeapInstance) {
              if (source instanceOf "android.app.Activity" && source["android.app.Activity", "mDestroyed"]?.value?.asBoolean == false) {
                // Find all attached views that have this activity as a base context
                val viewInstances =
                  graph.findClassByName("android.view.View")?.instances ?: emptySequence()
                viewInstances.mapNotNull { viewInstance ->
                  val viewAttached =
                    viewInstance["android.view.View", "mAttachInfo"]!!.value.isNonNullReference
                  if (viewAttached) {
                    val baseContext =
                      viewInstance["android.view.View", "mContext"]!!.valueAsInstance!!.unwrapComponentContext()
                    if (baseContext?.objectId == source.objectId) {
                      return@mapNotNull HeapNode(viewInstance.objectId)
                    }
                  }
                  null
                }
              } else if (source instanceOf "android.app.ActivityThread") {
                graph.findClassByName("android.app.Activity")!!
                  .instances
                  .filter { it["android.app.Activity", "mDestroyed"]?.value?.asBoolean == false }
                  .map { HeapNode(it.objectId) }
              } else {
                emptySequence()
              }
            } else {
              emptySequence()
            }
            references + additionalReferences
          }

          is UniqueInstanceNode -> {
            node.childNodes
          }
        }

      }

      val objectSizeCalculator = AndroidObjectSizeCalculator(graph)

      println("computing shallow sizes")
      var totalRetainedSize = 0
      val dominatorsByObjectId = objectIdsInTopologicalOrder.mapNotNull { objectIdOrNull ->
        objectIdOrNull?.let { node ->
          when (node) {
            is HeapNode -> {
              val name = graph.findObjectById(node.objectId).toString()
              val shallowSize = objectSizeCalculator.computeSize(node.objectId)
              totalRetainedSize += shallowSize
              node to DominatorObject(name, shallowSize = shallowSize)
            }

            is UniqueInstanceNode -> {
              node to DominatorObject(node.name, shallowSize = 0)
            }
          }

        }
      }.toMap()

      // TODO Why do we have null entries in there? Looks like just because the initial allocation
      // is all objects

      // We only update the retained sizes of objects in the dominator tree (i.e. reachable).
      // It's important to traverse in reverse topological order
      println("Creating dominator tree")
      for (dominatedObjectIndex in objectIdsInTopologicalOrder.indices.reversed()) {
        immediateDominators[dominatedObjectIndex]?.let { dominatorObjectId ->
          val dominatedObjectId = objectIdsInTopologicalOrder[dominatedObjectIndex]!!
          val dominator = dominatorsByObjectId.getValue(dominatorObjectId)
          val dominated = dominatorsByObjectId.getValue(dominatedObjectId)
          dominator.retainedSize += dominated.retainedSize
          dominator.dominatedNodes += dominated
          dominated.parent = dominated
        }
      }

      val rootDominatorsSortedByRetainedSize =
        dominatorsByObjectId.values.filter { it.parent == null }.sortedBy { -it.retainedSize }

      // Sanity check
      check(rootDominatorsSortedByRetainedSize.sumOf { it.retainedSize } == totalRetainedSize) {
        "Expected totalRetainedSize=$totalRetainedSize to be equal to sum ${rootDominatorsSortedByRetainedSize.sumOf { it.retainedSize }}"
      }

      val rootOfRoots = DominatorObject("root", shallowSize = 0)
      rootOfRoots.retainedSize = totalRetainedSize

      rootOfRoots.dominatedNodes.addAll(rootDominatorsSortedByRetainedSize)
      println("Creating root node")
      for (rootNode in rootOfRoots.dominatedNodes) {
        rootNode.parent = rootOfRoots
      }
      rootOfRoots
    }

    val csv = File(heapDumpFile.parent, "${heapDumpFile.nameWithoutExtension}.csv")
    csv.printWriter().use { out ->
      out.println("\"Child\",\"Parent\",\"Value\"")
      out.printNode(null, rootOfRoots, 4)
    }
  }

  fun PrintWriter.printNode(
    parent: DominatorObject?,
    node: DominatorObject,
    maxDepth: Int,
    depth: Int = 0
  ) {
    if (depth > maxDepth) {
      return
    }
    if (parent == null) {
      println("\"${node.name}\",\"\",\"${node.retainedSize}\"")
    } else {
      println("\"${node.name}\",\"${parent.name}\",\"${node.retainedSize}\"")
    }
    for (child in node.dominatedNodes) {
      printNode(node, child, maxDepth, depth + 1)
    }
  }

  class DominatorObject(
    val name: String,
    val shallowSize: Int
  ) {
    var parent: DominatorObject? = null
    val dominatedNodes = mutableListOf<DominatorObject>()
    var retainedSize = shallowSize
  }
}

@Suppress("NestedBlockDepth", "ReturnCount")
private fun HeapInstance.unwrapComponentContext(): HeapInstance? {
  val matchingClassName = instanceClass.classHierarchy.map { it.name }
    .firstOrNull {
      when (it) {
        "android.content.ContextWrapper",
        "android.app.Activity",
        "android.app.Application",
        "android.app.Service"
        -> true

        else -> false
      }
    }
    ?: return null

  if (matchingClassName != "android.content.ContextWrapper") {
    return this
  }

  var context = this
  val visitedInstances = mutableListOf<Long>()
  var keepUnwrapping = true
  while (keepUnwrapping) {
    visitedInstances += context.objectId
    keepUnwrapping = false
    val mBase = context["android.content.ContextWrapper", "mBase"]!!.value

    if (mBase.isNonNullReference) {
      val wrapperContext = context
      context = mBase.asObject!!.asInstance!!

      val contextMatchingClassName = context.instanceClass.classHierarchy.map { it.name }
        .firstOrNull {
          when (it) {
            "android.content.ContextWrapper",
            "android.app.Activity",
            "android.app.Application",
            "android.app.Service"
            -> true

            else -> false
          }
        }

      var isContextWrapper = contextMatchingClassName == "android.content.ContextWrapper"

      if (contextMatchingClassName == "android.app.Activity") {
        return context
      } else {
        if (wrapperContext instanceOf "com.android.internal.policy.DecorContext") {
          // mBase isn't an activity, let's unwrap DecorContext.mPhoneWindow.mContext instead
          val mPhoneWindowField =
            wrapperContext["com.android.internal.policy.DecorContext", "mPhoneWindow"]
          if (mPhoneWindowField != null) {
            val phoneWindow = mPhoneWindowField.valueAsInstance!!
            context = phoneWindow["android.view.Window", "mContext"]!!.valueAsInstance!!
            if (context instanceOf "android.app.Activity") {
              return context
            }
            isContextWrapper = context instanceOf "android.content.ContextWrapper"
          }
        }
        if (contextMatchingClassName == "android.app.Service" ||
          contextMatchingClassName == "android.app.Application"
        ) {
          return context
        }
        if (isContextWrapper &&
          // Avoids infinite loops
          context.objectId !in visitedInstances
        ) {
          keepUnwrapping = true
        }
      }
    }
  }
  return null
}

/**
 * Must properly implement equals & hashcode
 */
sealed interface NodeForDominators {
  data class HeapNode(val objectId: Long) : NodeForDominators
  class UniqueInstanceNode(
    val name: String,
    val childNodes: Sequence<NodeForDominators>
  ) : NodeForDominators {
    override fun equals(other: Any?): Boolean {
      return this === other
    }

    override fun hashCode(): Int {
      return System.identityHashCode(this)
    }
  }
}

class HelperNode private constructor(
  val objectId: Long,
  val name: String
) {
  val predecessors = mutableListOf<Pair<HelperNode, String>>()

  companion object {
    fun retrieve(
      cache: MutableMap<Long, HelperNode>,
      parent: HelperNode?,
      referenceName: String,
      objectId: Long,
      name: String
    ): HelperNode {
      val node = cache[objectId]
      val result = node ?: HelperNode(objectId, name).apply {
        cache[objectId] = this
      }

      return result.apply {
        if (parent != null) {
          result.predecessors += parent to referenceName
        }
      }
    }
  }
}
