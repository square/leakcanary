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

  // Initial import
  // https://cs.android.com/android/_/android/platform/libcore/+/51b1b6997fd3f980076b8081f7f1165ccc2a4008:ojluni/src/main/java/java/util/HashMap.java
  // Latest on master
  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/HashMap.java
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

              entries.mapNotNull { entry ->
                val value = entry[nodeClassName, "value"]!!.value
                if (value.isNonNullReference) {
                  val key = entry[nodeClassName, "key"]!!.value.asObject
                  val keyAsName = key?.asInstance?.readAsJavaString() ?: key?.toString() ?: "null"
                  HeapInstanceOutgoingRef(
                    declaringClass = instanceClass,
                    name = keyAsName,
                    objectId = value.asObjectId!!,
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