package shark

import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.HeapObject.HeapInstance
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.LOCAL
import shark.ReferencePattern.JavaLocalPattern
import shark.internal.JavaFrames
import shark.internal.ThreadObjects

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
    return JavaFrames.getByThreadObjectId(graph, source.objectId)?.let { frames ->
      frames.asSequence().map { frame ->
        Reference(
          valueObjectId = frame.id,
          // Java Frames always have low priority because their path is harder to understand
          // for developers
          isLowPriority = true,
          lazyDetailsResolver = {
            LazyDetails(
              // Unfortunately Android heap dumps do not include stack trace data, so
              // JavaFrame.frameNumber is always -1 and we cannot know which method is causing the
              // reference to be held.
              name = "",
              locationClassObjectId = threadClassId,
              locationType = LOCAL,
              matchedLibraryLeak = referenceMatcher as LibraryLeakReferenceMatcher?,
              isVirtual = true
            )
          }
        )
      }
    } ?: emptySequence()
  }
}
