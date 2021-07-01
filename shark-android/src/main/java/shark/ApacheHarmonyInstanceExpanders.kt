package shark

import shark.internal.InternalSharedHashMapExpander

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
      val linkedListClass = graph.findClassByName("java.util.LinkedList")
      val hasApacheHarmonyField = linkedListClass?.readRecordFields()
        ?.any { linkedListClass.instanceFieldName(it) == "voidLink" } ?: false
      val linkedListClassObjectId = linkedListClass?.objectId ?: 0
      return if (hasApacheHarmonyField) {
        MatchingInstanceExpander { instance ->
          if (hasApacheHarmonyField && instance.instanceClassId == linkedListClassObjectId) {
            val instanceClass = instance.instanceClass
            val firstLink = instance["java.util.LinkedList", "voidLink"]!!.valueAsInstance
            generateSequence(firstLink) { node ->
              node["java.util.LinkedList\$Link", "next"]!!.valueAsInstance
            }
              .withIndex()
              .mapNotNull { (index, node) ->
                val dataObjectId = node["java.util.LinkedList\$Link", "data"]!!.value.asObjectId
                dataObjectId?.run {
                  HeapInstanceOutgoingRef(
                    declaringClass = instanceClass,
                    name = "$index",
                    objectId = this,
                    isArrayLike = true
                  )
                }
              }
              .toList()
          } else {
            null
          }
        }
      } else {
        null
      }
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/ArrayList.java
  ARRAY_LIST {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val arrayListClass = graph.findClassByName("java.util.ArrayList")
      val isApacheHarmonyImpl = arrayListClass?.readRecordFields()
        ?.any { arrayListClass.instanceFieldName(it) == "array" } ?: false
      return if (isApacheHarmonyImpl) {
        val arrayListClassObjectId = arrayListClass!!.objectId
        MatchingInstanceExpander { instance ->
          if (instance.instanceClassId == arrayListClassObjectId) {
            val instanceClass = instance.instanceClass
            // "ArrayList.array" is never null
            val elementData =
              instance["java.util.ArrayList", "array"]!!.valueAsObjectArray!!.readElements()
            val size = instance["java.util.ArrayList", "size"]!!.value.asInt!!
            elementData.take(size).withIndex()
              .mapNotNull { (index, elementValue) ->
                if (elementValue.isNonNullReference) {
                  HeapInstanceOutgoingRef(
                    declaringClass = instanceClass,
                    name = "$index",
                    objectId = elementValue.asObjectId!!,
                    isArrayLike = true
                  )
                } else {
                  null
                }
              }.toList()
          } else {
            null
          }
        }
      } else {
        null
      }
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/concurrent/CopyOnWriteArrayList.java
  COPY_ON_WRITE_ARRAY_LIST {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val arrayListClass = graph.findClassByName("java.util.concurrent.CopyOnWriteArrayList")
      val isOpenJdkImpl = arrayListClass?.readRecordFields()
        ?.any { arrayListClass.instanceFieldName(it) == "elements" } ?: false
      return if (isOpenJdkImpl) {
        val arrayListClassObjectId = arrayListClass!!.objectId
        MatchingInstanceExpander { instance ->
          if (instance.instanceClassId == arrayListClassObjectId) {
            val instanceClass = instance.instanceClass
            // "CopyOnWriteArrayList.elements" is never null
            val elements =
              instance["java.util.concurrent.CopyOnWriteArrayList", "elements"]!!.valueAsObjectArray!!.readElements()
            elements.withIndex()
              .mapNotNull { (index, elementValue) ->
                if (elementValue.isNonNullReference) {
                  HeapInstanceOutgoingRef(
                    declaringClass = instanceClass,
                    name = "$index",
                    objectId = elementValue.asObjectId!!,
                    isArrayLike = true
                  )
                } else {
                  null
                }
              }.toList()
          } else {
            null
          }
        }
      } else {
        null
      }
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
        hashMapClassName = "java.util.HashMap",
        tableFieldName = "table",
        nodeClassName = "java.util.HashMap\$HashMapEntry",
        nodeNextFieldName = "next",
        nodeKeyFieldName = "key",
        nodeValueFieldName = "value",
        keyName = "key()",
        keysOnly = false,
        matches = {
          val instanceClassId = it.instanceClassId
          instanceClassId == hashMapClassId ||  instanceClassId == linkedHashMapClassId
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
            hashMapClassName = "java.util.HashMap",
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