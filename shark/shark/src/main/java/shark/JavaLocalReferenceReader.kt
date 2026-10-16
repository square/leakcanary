package shark

import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniLocal
import shark.GcRoot.NativeStack
import shark.HeapObject.HeapInstance
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.LOCAL
import shark.ReferenceLocationType.NATIVE_LOCAL
import shark.ReferencePattern.JavaLocalPattern
import shark.internal.ThreadObjects
import shark.internal.ThreadStackRoots

/**
 * Reads the roots on a thread's stack as low priority references from that thread: a [LOCAL] one for
 * each `JAVA_FRAME` root the heap dump has for it, and a [NATIVE_LOCAL] one for each `JNI_LOCAL` and
 * `NATIVE_STACK` root. [MatchingGcRootProvider] leaves those roots out, so a leak trace through one
 * names the thread.
 */
class JavaLocalReferenceReader(
  val graph: HeapGraph,
  referenceMatchers: List<ReferenceMatcher>
) : VirtualInstanceReferenceReader {

  private val threadClassObjectIds: Set<Long> =
    graph.findClassByName(Thread::class.java.name)?.let { threadClass ->
      setOf(threadClass.objectId) + (threadClass.subclasses
        .map { it.objectId }
        .toSet())
    }?: emptySet()

  private val threadNameReferenceMatchers: Map<String, ReferenceMatcher>

  init {
    val threadNames = mutableMapOf<String, ReferenceMatcher>()
    referenceMatchers.filterFor(graph).forEach { referenceMatcher ->
      val pattern = referenceMatcher.pattern
      if (pattern is JavaLocalPattern) {
        threadNames[pattern.threadName] = referenceMatcher
      }
    }
    this.threadNameReferenceMatchers = threadNames
  }

  override fun matches(instance: HeapInstance): Boolean {
    return instance.instanceClassId in threadClassObjectIds &&
      ThreadObjects.getByThreadObjectId(graph, instance.objectId) != null
  }

  override val readsCutSet = false

  override fun read(source: HeapInstance): Sequence<Reference> {
    val referenceMatcher =  source[Thread::class, "name"]?.value?.readAsJavaString()?.let { threadName ->
      threadNameReferenceMatchers[threadName]
    }

    if (referenceMatcher is IgnoredReferenceMatcher) {
      return emptySequence()
    }
    val threadClassId = source.instanceClassId
    return ThreadStackRoots.getByThreadObjectId(graph, source.objectId)?.let { stackRoots ->
      stackRoots.asSequence().map { stackRoot ->
        Reference(
          valueObjectId = stackRoot.id,
          // Locals always have low priority because their path is harder to understand
          // for developers
          isLowPriority = true,
          lazyDetailsResolver = {
            LazyDetails(
              // Unfortunately Android heap dumps do not include stack trace data, so
              // JavaFrame.frameNumber is always -1 and we cannot know which method is causing the
              // reference to be held.
              name = "",
              locationClassObjectId = threadClassId,
              locationType = when (stackRoot) {
                is JavaFrame -> LOCAL
                is JniLocal, is NativeStack -> NATIVE_LOCAL
                else -> error("Not a root on a thread's stack: $stackRoot")
              },
              matchedLibraryLeak = referenceMatcher as LibraryLeakReferenceMatcher?,
              isVirtual = true
            )
          }
        )
      }
    } ?: emptySequence()
  }
}
