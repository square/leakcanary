package shark

import shark.internal.InternalSharedArrayListExpander
import shark.internal.InternalSharedHashMapExpander
import shark.internal.InternalSharedLinkedListExpander

/**
 * Defines [MatchingInstanceExpander] factories for common OpenJDK data structures.
 *
 * Note: the expanders target the direct classes and don't target subclasses, as these might
 * include additional out going references that would be missed.
 */
enum class OpenJdkInstanceExpanders : (HeapGraph) -> MatchingInstanceExpander? {

  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/LinkedList.java
  LINKED_LIST {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val linkedListClass = graph.findClassByName("java.util.LinkedList") ?: return null
      val isOpenJdkImpl = linkedListClass.readRecordFields()
        .any { linkedListClass.instanceFieldName(it) == "first" }

      if (!isOpenJdkImpl) {
        return null
      }
      return InternalSharedLinkedListExpander(
        classObjectId = linkedListClass.objectId,
        headFieldName = "first",
        nodeClassName = "java.util.LinkedList\$Node",
        nodeNextFieldName = "next",
        nodeElementFieldName = "item",
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/ArrayList.java
  ARRAY_LIST {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val arrayListClass = graph.findClassByName("java.util.ArrayList") ?: return null

      val isOpenJdkImpl = arrayListClass.readRecordFields()
        .any { arrayListClass.instanceFieldName(it) == "elementData" }

      if (!isOpenJdkImpl) {
        return null
      }

      return InternalSharedArrayListExpander(
        className = "java.util.ArrayList",
        classObjectId = arrayListClass.objectId,
        elementArrayName = "elementData",
        sizeFieldName = "size",
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/concurrent/CopyOnWriteArrayList.java;bpv=0;bpt=1
  COPY_ON_WRITE_ARRAY_LIST {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val arrayListClass = graph.findClassByName("java.util.concurrent.CopyOnWriteArrayList") ?: return null

      val isOpenJdkImpl = arrayListClass.readRecordFields()
        .any { arrayListClass.instanceFieldName(it) == "array" }

      if (!isOpenJdkImpl) {
        return null
      }

      return InternalSharedArrayListExpander(
        className = "java.util.concurrent.CopyOnWriteArrayList",
        classObjectId = arrayListClass.objectId,
        elementArrayName = "array",
        sizeFieldName = null,
      )
    }
  },

  // Initial import
  // https://cs.android.com/android/_/android/platform/libcore/+/51b1b6997fd3f980076b8081f7f1165ccc2a4008:ojluni/src/main/java/java/util/HashMap.java
  // Latest on master
  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/HashMap.java
  /**
   * Handles HashMap & LinkedHashMap
   */
  HASH_MAP {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val hashMapClass = graph.findClassByName("java.util.HashMap") ?: return null

      // No loadFactor field in the Apache Harmony impl.
      val isOpenJdkImpl = hashMapClass.readRecordFields()
        .any { hashMapClass.instanceFieldName(it) == "loadFactor" }

      if (!isOpenJdkImpl) {
        return null
      }

      val linkedHashMapClass = graph.findClassByName("java.util.LinkedHashMap")
      // Initially Entry, changed to Node in JDK 1.8
      val nodeClassName = if (graph.findClassByName("java.util.HashMap\$Entry") != null) {
        "java.util.HashMap\$Entry"
      } else {
        "java.util.HashMap\$Node"
      }

      val hashMapClassId = hashMapClass.objectId
      val linkedHashMapClassId = linkedHashMapClass?.objectId ?: 0

      return InternalSharedHashMapExpander(
        className = "java.util.HashMap",
        tableFieldName = "table",
        nodeClassName = nodeClassName,
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

  // TODO Ordering tests? for lists and maps
  // TODO Better key names? whatever I did in sharkapp?
  // Can all hashmap impls share the same core code with some params? Also array list? and others?

  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/concurrent/ConcurrentHashMap.java
  // Note: structure of impl shared by OpenJDK & Apache Harmony.
  CONCURRENT_HASH_MAP {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val hashMapClass =
        graph.findClassByName("java.util.concurrent.ConcurrentHashMap") ?: return null

      val hashMapClassId = hashMapClass.objectId
      return InternalSharedHashMapExpander(
        className = "java.util.concurrent.ConcurrentHashMap",
        tableFieldName = "table",
        nodeClassName = "java.util.concurrent.ConcurrentHashMap\$Node",
        nodeNextFieldName = "next",
        nodeKeyFieldName = "key",
        nodeValueFieldName = "val",
        keyName = "key()",
        keysOnly = false,
        matches = { it.instanceClassId == hashMapClassId },
        declaringClass = { it.instanceClass }
      )
    }
  },

  /**
   * Handles HashSet & LinkedHashSet
   */
  HASH_SET {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val hashSetClass = graph.findClassByName("java.util.HashSet") ?: return null

      val isOpenJdkImpl = hashSetClass.readRecordFields()
        .any { hashSetClass.instanceFieldName(it) == "map" }

      if (!isOpenJdkImpl) {
        return null
      }

      val linkedHashSetClass = graph.findClassByName("java.util.LinkedHashSet")
      // Initially Entry, changed to Node in JDK 1.8
      val nodeClassName = if (graph.findClassByName("java.util.HashMap\$Entry") != null) {
        "java.util.HashMap\$Entry"
      } else {
        "java.util.HashMap\$Node"
      }
      val hashSetClassId = hashSetClass.objectId
      val linkedHashSetClassId = linkedHashSetClass?.objectId ?: 0
      return MatchingInstanceExpander { instance ->
        val instanceClassId = instance.instanceClassId
        if (instanceClassId == hashSetClassId || instanceClassId == linkedHashSetClassId) {
          // "HashSet.map" is never null.
          val map = instance["java.util.HashSet", "map"]!!.valueAsInstance!!
          InternalSharedHashMapExpander(
            className = "java.util.HashMap",
            tableFieldName = "table",
            nodeClassName = nodeClassName,
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