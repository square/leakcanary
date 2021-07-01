package shark

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
      val linkedListClass = graph.findClassByName("java.util.LinkedList")
      val isOpenJdkImpl = linkedListClass?.readRecordFields()
        ?.any { linkedListClass.instanceFieldName(it) == "first" } ?: false
      return if (isOpenJdkImpl) {
        val linkedListClassObjectId = linkedListClass!!.objectId
        MatchingInstanceExpander { instance ->
          if (instance.instanceClassId == linkedListClassObjectId) {
            val instanceClass = instance.instanceClass
            // "LinkedList.first" may be null, in that case we generate an empty sequence.
            val firstNode = instance["java.util.LinkedList", "first"]!!.valueAsInstance
            generateSequence(firstNode) { node ->
              node["java.util.LinkedList\$Node", "next"]!!.valueAsInstance
            }
              .withIndex()
              .mapNotNull { (index, node) ->
                val itemObjectId = node["java.util.LinkedList\$Node", "item"]!!.value.asObjectId
                itemObjectId?.run {
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

  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/ArrayList.java
  ARRAY_LIST {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val arrayListClass = graph.findClassByName("java.util.ArrayList")
      val isOpenJdkImpl = arrayListClass?.readRecordFields()
        ?.any { arrayListClass.instanceFieldName(it) == "elementData" } ?: false
      return if (isOpenJdkImpl) {
        val arrayListClassObjectId = arrayListClass!!.objectId
        MatchingInstanceExpander { instance ->
          if (instance.instanceClassId == arrayListClassObjectId) {
            val instanceClass = instance.instanceClass
            // "ArrayList.elementData" is never null
            val elementData =
              instance["java.util.ArrayList", "elementData"]!!.valueAsObjectArray!!.readElements()
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

  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/concurrent/CopyOnWriteArrayList.java;bpv=0;bpt=1
  COPY_ON_WRITE_ARRAY_LIST {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val arrayListClass = graph.findClassByName("java.util.concurrent.CopyOnWriteArrayList")
      val isOpenJdkImpl = arrayListClass?.readRecordFields()
        ?.any { arrayListClass.instanceFieldName(it) == "array" } ?: false
      return if (isOpenJdkImpl) {
        val arrayListClassObjectId = arrayListClass!!.objectId
        MatchingInstanceExpander { instance ->
          if (instance.instanceClassId == arrayListClassObjectId) {
            val instanceClass = instance.instanceClass
            // "CopyOnWriteArrayList.array" is never null
            val array =
              instance["java.util.concurrent.CopyOnWriteArrayList", "array"]!!.valueAsObjectArray!!.readElements()
            array.withIndex()
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

  // Initial import
  // https://cs.android.com/android/_/android/platform/libcore/+/51b1b6997fd3f980076b8081f7f1165ccc2a4008:ojluni/src/main/java/java/util/HashMap.java
  // Latest on master
  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/HashMap.java
  /**
   * Handles HashMap & LinkedHashMap
   */
  HASH_MAP {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val hashMapClass = graph.findClassByName("java.util.HashMap")

      // No loadFactor field in the Apache Harmony impl.
      val isOpenJdkImpl = hashMapClass?.readRecordFields()
        ?.any { hashMapClass.instanceFieldName(it) == "loadFactor" } ?: false

      return if (isOpenJdkImpl) {
        val linkedHashMapClass = graph.findClassByName("java.util.LinkedHashMap")
        // Initially Entry, changed to Node in JDK 1.8
        val nodeClassName = if (graph.findClassByName("java.util.HashMap\$Entry") != null) {
          "java.util.HashMap\$Entry"
        } else {
          "java.util.HashMap\$Node"
        }

        val hashMapClassId = hashMapClass!!.objectId
        val linkedHashMapClassId = linkedHashMapClass?.objectId ?: 0
        MatchingInstanceExpander { instance ->
          val instanceClassId = instance.instanceClassId
          if (instanceClassId == hashMapClassId || instanceClassId == linkedHashMapClassId) {
            val instanceClass = instance.instanceClass
            val table = instance["java.util.HashMap", "table"]!!.valueAsObjectArray
            // Latest OpenJDK starts with a null table until an entry is added.
            if (table != null) {
              val entries = table.readElements().mapNotNull { entryRef ->
                if (entryRef.isNonNullReference) {
                  val entry = entryRef.asObject!!.asInstance!!
                  generateSequence(entry) { node ->
                    node[nodeClassName, "next"]!!.valueAsInstance
                  }
                } else {
                  null
                }
              }.flatten()
              entries.flatMap { entry ->
                val key = entry[nodeClassName, "key"]!!.value
                val keyRef = if (key.isNonNullReference) {
                  HeapInstanceOutgoingRef(
                    declaringClass = instanceClass,
                    // All entries are represented by the same key name, "key()"
                    name = "key()",
                    objectId = key.asObjectId!!,
                    isArrayLike = true
                  )
                } else null

                val value = entry[nodeClassName, "value"]!!.value
                val valueRef = if (value.isNonNullReference) {
                  val keyAsName = key.asObject?.asInstance?.readAsJavaString() ?: key.asObject?.toString() ?: "null"
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
          } else {
            null
          }
        }
      } else {
        null
      }
    }
  },

  // TODO Ordering tests? for lists and maps
  // TODO Better key names? whatever I did in sharkapp?
  // Can all hashmap impls share the same core code with some params?

  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/concurrent/ConcurrentHashMap.java
  // Note: structure of impl shared by OpenJDK & Apache Harmony.
  CONCURRENT_HASH_MAP {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val hashMapClass = graph.findClassByName("java.util.concurrent.ConcurrentHashMap")

      return if (hashMapClass != null) {
        val hashMapClassId = hashMapClass.objectId
        MatchingInstanceExpander { instance ->
          val instanceClassId = instance.instanceClassId
          if (instanceClassId == hashMapClassId) {
            val instanceClass = instance.instanceClass
            val table = instance["java.util.concurrent.ConcurrentHashMap", "table"]!!.valueAsObjectArray
            // Starts with a null table until an entry is added.
            if (table != null) {
              val entries = table.readElements().mapNotNull { entryRef ->
                if (entryRef.isNonNullReference) {
                  val entry = entryRef.asObject!!.asInstance!!
                  generateSequence(entry) { node ->
                    node["java.util.concurrent.ConcurrentHashMap\$Node", "next"]!!.valueAsInstance
                  }
                } else {
                  null
                }
              }.flatten()
              entries.flatMap { entry ->
                val key = entry["java.util.concurrent.ConcurrentHashMap\$Node", "key"]!!.value
                val keyRef = if (key.isNonNullReference) {
                  HeapInstanceOutgoingRef(
                    declaringClass = instanceClass,
                    // All entries are represented by the same key name, "key()"
                    name = "key()",
                    objectId = key.asObjectId!!,
                    isArrayLike = true
                  )
                } else null

                val value = entry["java.util.concurrent.ConcurrentHashMap\$Node", "val"]!!.value
                val valueRef = if (value.isNonNullReference) {
                  val keyAsName = key.asObject?.asInstance?.readAsJavaString() ?: key.asObject?.toString() ?: "null"
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
          } else {
            null
          }
        }
      } else {
        null
      }
    }
  },

  /**
   * Handles HashSet & LinkedHashSet
   */
  HASH_SET {
    override fun invoke(graph: HeapGraph): MatchingInstanceExpander? {
      val hashSetClass = graph.findClassByName("java.util.HashSet")

      val isOpenJdkImpl = hashSetClass?.readRecordFields()
        ?.any { hashSetClass.instanceFieldName(it) == "map" } ?: false

      return if (isOpenJdkImpl) {
        val linkedHashSetClass = graph.findClassByName("java.util.LinkedHashSet")
        // Initially Entry, changed to Node in JDK 1.8
        val nodeClassName = if (graph.findClassByName("java.util.HashMap\$Entry") != null) {
          "java.util.HashMap\$Entry"
        } else {
          "java.util.HashMap\$Node"
        }

        val hashSetClassId = hashSetClass!!.objectId
        val linkedHashSetClassId = linkedHashSetClass?.objectId ?: 0
        MatchingInstanceExpander { instance ->
          val instanceClassId = instance.instanceClassId
          if (instanceClassId == hashSetClassId || instanceClassId == linkedHashSetClassId) {
            val instanceClass = instance.instanceClass
            // "HashSet.map" is never null.
            val map = instance["java.util.HashSet", "map"]!!.valueAsInstance!!
            val table = map["java.util.HashMap", "table"]!!.valueAsObjectArray
            // Latest OpenJDK starts with a null table until an entry is added.
            if (table != null) {
              val entries = table.readElements().mapNotNull { entryRef ->
                if (entryRef.isNonNullReference) {
                  val entry = entryRef.asObject!!.asInstance!!
                  generateSequence(entry) { node ->
                    node[nodeClassName, "next"]!!.valueAsInstance
                  }
                } else {
                  null
                }
              }.flatten()

              entries.mapNotNull { entry ->
                val key = entry[nodeClassName, "key"]!!.value
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