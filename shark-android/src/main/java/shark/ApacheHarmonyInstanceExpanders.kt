package shark

import shark.internal.InternalSharedArrayListExpander
import shark.internal.InternalSharedHashMapExpander
import shark.internal.InternalSharedLinkedListExpander

/**
 * Defines [MatchingInstanceExpander] factories for common Apache Harmony data structures.
 *
 * Note: the expanders target the direct classes and don't target subclasses, as these might
 * include additional out going references that would be missed.
 */
enum class ApacheHarmonyInstanceExpanders : (HeapGraph) -> MatchingInstanceExpander? {

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/LinkedList.java
  LINKED_LIST {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val linkedListClass = graph.findClassByName("java.util.LinkedList") ?: return null
      val isApacheHarmonyImpl = linkedListClass.readRecordFields()
        .any { linkedListClass.instanceFieldName(it) == "voidLink" }

      if (!isApacheHarmonyImpl) {
        return null
      }
      return InternalSharedLinkedListExpander(
        classObjectId = linkedListClass.objectId,
        headFieldName = "voidLink",
        nodeClassName = "java.util.LinkedList\$Link",
        nodeNextFieldName = "next",
        nodeElementFieldName = "data",
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/ArrayList.java
  ARRAY_LIST {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val arrayListClass = graph.findClassByName("java.util.ArrayList") ?: return null

      val isApacheHarmonyImpl = arrayListClass.readRecordFields()
        .any { arrayListClass.instanceFieldName(it) == "array" }

      if (!isApacheHarmonyImpl) {
        return null
      }

      return InternalSharedArrayListExpander(
        className = "java.util.ArrayList",
        classObjectId = arrayListClass.objectId,
        elementArrayName = "array",
        sizeFieldName = "size",
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/concurrent/CopyOnWriteArrayList.java
  COPY_ON_WRITE_ARRAY_LIST {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val arrayListClass =
        graph.findClassByName("java.util.concurrent.CopyOnWriteArrayList") ?: return null

      val isApacheHarmonyImpl = arrayListClass.readRecordFields()
        .any { arrayListClass.instanceFieldName(it) == "elements" }

      if (!isApacheHarmonyImpl) {
        return null
      }

      return InternalSharedArrayListExpander(
        className = "java.util.concurrent.CopyOnWriteArrayList",
        classObjectId = arrayListClass.objectId,
        elementArrayName = "elements",
        sizeFieldName = null,
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/HashMap.java
  /**
   * Handles HashMap & LinkedHashMap
   */
  HASH_MAP {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val hashMapClass = graph.findClassByName("java.util.HashMap") ?: return null

      // No loadFactor field in the Apache Harmony impl.
      val isOpenJdkImpl = hashMapClass.readRecordFields()
        .any { hashMapClass.instanceFieldName(it) == "loadFactor" }

      if (isOpenJdkImpl) {
        return null
      }
      val linkedHashMapClass = graph.findClassByName("java.util.LinkedHashMap")

      val hashMapClassId = hashMapClass.objectId
      val linkedHashMapClassId = linkedHashMapClass?.objectId ?: 0

      return InternalSharedHashMapExpander(
        className = "java.util.HashMap",
        tableFieldName = "table",
        nodeClassName = "java.util.HashMap\$HashMapEntry",
        nodeNextFieldName = "next",
        nodeKeyFieldName = "key",
        nodeValueFieldName = "value",
        keyName = "key()",
        keysOnly = false,
        matches = {
          val instanceClassId = it.instanceClassId
          instanceClassId == hashMapClassId || instanceClassId == linkedHashMapClassId
        },
        declaringClass = { it.instanceClass }
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/HashSet.java
  /**
   * Handles HashSet & LinkedHashSet
   */
  HASH_SET {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val hashSetClass = graph.findClassByName("java.util.HashSet") ?: return null

      val isApacheHarmonyImpl = hashSetClass.readRecordFields()
        .any { hashSetClass.instanceFieldName(it) == "backingMap" }

      if (!isApacheHarmonyImpl) {
        return null
      }

      val linkedHashSetClass = graph.findClassByName("java.util.LinkedHashSet")
      val hashSetClassId = hashSetClass.objectId
      val linkedHashSetClassId = linkedHashSetClass?.objectId ?: 0
      return MatchingInstanceExpander { instance ->
        val instanceClassId = instance.instanceClassId
        if (instanceClassId == hashSetClassId || instanceClassId == linkedHashSetClassId) {
          // "HashSet.backingMap" is never null.
          val map = instance["java.util.HashSet", "backingMap"]!!.valueAsInstance!!
          InternalSharedHashMapExpander(
            className = "java.util.HashMap",
            tableFieldName = "table",
            nodeClassName = "java.util.HashMap\$HashMapEntry",
            nodeNextFieldName = "next",
            nodeKeyFieldName = "key",
            nodeValueFieldName = "value",
            keyName = "element()",
            keysOnly = true,
            matches = { true },
            declaringClass = { instance.instanceClass }
          ).expandOutgoingRefs(map)
        } else {
          null
        }
      }
    }
  }

  ;
}