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

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/HashMap.java
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
            val table = instance["java.util.HashMap", "table"]!!.valueAsObjectArray
            // Latest OpenJDK starts with a null table until an entry is added.
            if (table != null) {
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
                val value = entry["java.util.HashMap\$HashMapEntry", "value"]!!.value
                if (value.isNonNullReference) {
                  val key = entry["java.util.HashMap\$HashMapEntry", "key"]!!.value.asObject
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