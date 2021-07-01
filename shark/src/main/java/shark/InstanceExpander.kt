package shark

import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord.FieldRecord
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT
import shark.internal.FieldIdReader
import kotlin.LazyThreadSafetyMode.NONE

/**
 * TODO Support Vector, Android message, ThreadLocal$ThreadLocalMap$Entry
 * ConcurrentHashMap =>  single for open jdk & harmony, very similar to hashmap. could
 * also merge the 3.
 * ThreadLocal$Values.table
 *
 * MessageQueue.mMessages => Message.callback, Message.obj
 *  => should emit Message objects instead of obj + callback
 */
fun interface InstanceExpander {
  /**
   * Returns the list of non null outgoing references from [instance]. Outgoing refs
   * can be actual fields or they can be synthetic fields when simplifying known data
   * structures.
   *
   * The returned list is sorted by [HeapInstanceOutgoingRef.name] in alphanumeric order to
   * ensure consistent graph traversal across heap dumps (fields: class structure can evolve,
   * synthesized maps: keys always in the same order).
   *
   * The returned list may contain several [HeapInstanceOutgoingRef] with an identical
   * [HeapInstanceOutgoingRef.objectId].
   */
  fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceOutgoingRef>
}

/**
 * Expands instance fields that hold non null references.
 */
class FieldInstanceExpander(
  heapGraph: HeapGraph
) : InstanceExpander {

  private val javaLangObjectId: Long

  init {
    val objectClass = heapGraph.findClassByName("java.lang.Object")
    javaLangObjectId = objectClass?.objectId ?: -1
  }

  override fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceOutgoingRef> =
    with(instance) {
      val classHierarchy = instanceClass.classHierarchyWithoutJavaLangObject(javaLangObjectId)

      // Assigning to local variable to avoid repeated lookup and cast:
      // HeapInstance.graph casts HeapInstance.hprofGraph to HeapGraph in its getter
      val hprofGraph = graph
      val fieldReader by lazy(NONE) {
        FieldIdReader(readRecord(), hprofGraph.identifierByteSize)
      }
      val result = mutableListOf<HeapInstanceOutgoingRef>()
      var skipBytesCount = 0

      for (heapClass in classHierarchy) {
        for (fieldRecord in heapClass.readRecordFields()) {
          if (fieldRecord.type != PrimitiveType.REFERENCE_HPROF_TYPE) {
            // Skip all fields that are not references. Track how many bytes to skip
            skipBytesCount += hprofGraph.getRecordSize(fieldRecord)
          } else {
            // Skip the accumulated bytes offset
            fieldReader.skipBytes(skipBytesCount)
            skipBytesCount = 0
            val objectId = fieldReader.readId()
            if (objectId != 0L) {
              result.add(
                HeapInstanceOutgoingRef(
                  declaringClass = heapClass,
                  name = heapClass.instanceFieldName(fieldRecord),
                  objectId = objectId,
                  isArrayLike = false
                )
              )
            }
          }
        }
      }
      result.sortBy { it.name }
      result
    }

  /**
   * Returns class hierarchy for an instance, but without it's root element, which is always
   * java.lang.Object.
   * Why do we want class hierarchy without java.lang.Object?
   * In pre-M there were no ref fields in java.lang.Object; and FieldIdReader wouldn't be created
   * Android M added shadow$_klass_ reference to class, so now it triggers extra record read.
   * Solution: skip heap class for java.lang.Object completely when reading the records
   * @param javaLangObjectId ID of the java.lang.Object to run comparison against
   */
  private fun HeapClass.classHierarchyWithoutJavaLangObject(
    javaLangObjectId: Long
  ): List<HeapClass> {
    val result = mutableListOf<HeapClass>()
    var parent: HeapClass? = this
    while (parent != null && parent.objectId != javaLangObjectId) {
      result += parent
      parent = parent.superclass
    }
    return result
  }

  private fun HeapGraph.getRecordSize(field: FieldRecord) =
    when (field.type) {
      PrimitiveType.REFERENCE_HPROF_TYPE -> identifierByteSize
      BOOLEAN.hprofType -> 1
      CHAR.hprofType -> 2
      FLOAT.hprofType -> 4
      DOUBLE.hprofType -> 8
      BYTE.hprofType -> 1
      SHORT.hprofType -> 2
      INT.hprofType -> 4
      LONG.hprofType -> 8
      else -> throw IllegalStateException("Unknown type ${field.type}")
    }
}

fun interface MatchingInstanceExpander {
  /**
   * Same as [InstanceExpander.expandOutgoingRefs] but may return null if this
   * [MatchingInstanceExpander] implementation isn't able to expand [instance].
   */
  fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceOutgoingRef>?
}

/**
 * A [InstanceExpander] that first delegates expanding to [matchingExpanders] in order,
 * and then proceeds with [fieldInstanceExpander]. This means any synthetic ref will be on the
 * shortest path, but we still explore the entire data structure so that we correctly track
 * which objects have been visited and correctly compute dominators and retained size.
 */
class MatchingChainedInstanceExpander(
  private val matchingExpanders: List<MatchingInstanceExpander>,
  private val fieldInstanceExpander: FieldInstanceExpander
) : InstanceExpander {

  override fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceOutgoingRef> {
    return expandMatchingOutgoingRefs(instance) + fieldInstanceExpander.expandOutgoingRefs(instance)
  }

  private fun expandMatchingOutgoingRefs(instance: HeapInstance): List<HeapInstanceOutgoingRef> {
    for (expander in matchingExpanders) {
      val refs = expander.expandOutgoingRefs(instance)
      if (refs != null) {
        return refs
      }
    }
    return emptyList()
  }

  companion object {
    fun factory(expanderFactories: List<(HeapGraph) -> MatchingInstanceExpander?>): (HeapGraph) -> InstanceExpander {
      return { graph ->
        val matchingExpanders = expanderFactories.mapNotNull { it(graph) }
        MatchingChainedInstanceExpander(matchingExpanders, FieldInstanceExpander(graph))
      }
    }
  }
}
