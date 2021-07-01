package shark

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
      val hashMapClass = graph.findClassByName("java.util.HashMap")

      // No loadFactor field in the Apache Harmony impl.
      val isOpenJdkImpl = hashMapClass?.readRecordFields()
        ?.any { hashMapClass.instanceFieldName(it) == "loadFactor" } ?: false
      val isApacheHarmonyImpl = !isOpenJdkImpl

      return if (isApacheHarmonyImpl) {
        val linkedHashMapClass = graph.findClassByName("java.util.LinkedHashMap")

        val hashMapClassId = hashMapClass!!.objectId
        val linkedHashMapClassId = linkedHashMapClass?.objectId ?: 0
        MatchingInstanceExpander { instance ->
          val instanceClassId = instance.instanceClassId
          if (instanceClassId == hashMapClassId || instanceClassId == linkedHashMapClassId) {
            val instanceClass = instance.instanceClass
            // table is never
            val table = instance["java.util.HashMap", "table"]!!.valueAsObjectArray!!
            val entries = table.readElements().mapNotNull { entryRef ->
              if (entryRef.isNonNullReference) {
                val entry = entryRef.asObject!!.asInstance!!
                generateSequence(entry) { node ->
                  node["java.util.HashMap\$HashMapEntry", "next"]!!.valueAsInstance
                }
              } else {
                null
              }
            }.flatten()
            entries.flatMap { entry ->
              val key = entry["java.util.HashMap\$HashMapEntry", "key"]!!.value
              val keyRef = if (key.isNonNullReference) {
                HeapInstanceOutgoingRef(
                  declaringClass = instanceClass,
                  // All entries are represented by the same key name, "key()"
                  name = "key()",
                  objectId = key.asObjectId!!,
                  isArrayLike = true
                )
              } else null

              val value = entry["java.util.HashMap\$HashMapEntry", "value"]!!.value
              val valueRef = if (value.isNonNullReference) {
                val keyAsName =
                  key.asObject?.asInstance?.readAsJavaString() ?: key.asObject?.toString() ?: "null"
                HeapInstanceOutgoingRef(
                  declaringClass = instanceClass,
                  name = keyAsName,
                  objectId = value.asObjectId!!,
                  isArrayLike = true
                )
              } else null
              sequenceOf(keyRef, valueRef)
            }.filterNotNull().toList()
          } else {
            null
          }
        }
      } else {
        null
      }
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/HashSet.java
  /**
   * Handles HashSet & LinkedHashSet
   */
  HASH_SET {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val hashSetClass = graph.findClassByName("java.util.HashSet")

      val isApacheHarmonyImpl = hashSetClass?.readRecordFields()
        ?.any { hashSetClass.instanceFieldName(it) == "backingMap" } ?: false

      return if (isApacheHarmonyImpl) {
        val linkedHashSetClass = graph.findClassByName("java.util.LinkedHashSet")
        val hashSetClassId = hashSetClass!!.objectId
        val linkedHashSetClassId = linkedHashSetClass?.objectId ?: 0
        MatchingInstanceExpander { instance ->
          val instanceClassId = instance.instanceClassId
          if (instanceClassId == hashSetClassId || instanceClassId == linkedHashSetClassId) {
            val instanceClass = instance.instanceClass
            // "HashSet.map" is never null.
            val map = instance["java.util.HashSet", "backingMap"]!!.valueAsInstance!!
            // table is never null
            val table = map["java.util.HashMap", "table"]!!.valueAsObjectArray!!
            val entries = table.readElements().mapNotNull { entryRef ->
              if (entryRef.isNonNullReference) {
                val entry = entryRef.asObject!!.asInstance!!
                generateSequence(entry) { node ->
                  node["java.util.HashMap\$HashMapEntry", "next"]!!.valueAsInstance
                }
              } else {
                null
              }
            }.flatten()

            entries.mapNotNull { entry ->
              val key = entry["java.util.HashMap\$HashMapEntry", "key"]!!.value
              if (key.isNonNullReference) {
                HeapInstanceOutgoingRef(
                  declaringClass = instanceClass,
                  // All entries are represented by the same key name, "element()"
                  name = "element()",
                  objectId = key.asObjectId!!,
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
  }

  ;
}