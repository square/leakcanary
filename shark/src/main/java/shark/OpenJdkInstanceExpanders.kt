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
      val hasOpenJdkField = linkedListClass?.readRecordFields()
        ?.any { linkedListClass.instanceFieldName(it) == "first" } ?: false
      val linkedListClassObjectId = linkedListClass?.objectId ?: 0
      return if (hasOpenJdkField) {
        MatchingInstanceExpander { instance ->
          if (hasOpenJdkField && instance.instanceClassId == linkedListClassObjectId) {
            val instanceClass = instance.instanceClass
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
  }
  ;
}