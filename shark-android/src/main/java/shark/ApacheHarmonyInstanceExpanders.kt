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
  }
  ;
}