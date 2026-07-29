package shark

import shark.ValueHolder.Companion.NULL_REFERENCE

/**
 * A `java.util.LinkedList` holding [items], shaped the way the OpenJDK implementation is so that
 * [OpenJdkInstanceRefReaders.LINKED_LIST] expands it.
 */
internal fun HprofWriterHelper.linkedListInStaticField(vararg items: ValueHolder.ReferenceHolder) {
  val nodeClassId = clazz(
    "java.util.LinkedList\$Node",
    fields = listOf(
      "item" to ValueHolder.ReferenceHolder::class,
      "next" to ValueHolder.ReferenceHolder::class,
    )
  )
  val linkedListClassId = clazz(
    "java.util.LinkedList",
    fields = listOf("first" to ValueHolder.ReferenceHolder::class)
  )
  val first = items.foldRight(nullReference()) { item, next ->
    instance(nodeClassId, listOf(item, next))
  }
  clazz(
    "ClassWithStatics",
    staticFields = listOf("list" to instance(linkedListClassId, listOf(first)))
  )
}

/** The single node of [HeapTraversalOutput.shortestPathTree] whose name ends with [nameSuffix]. */
internal fun HeapTraversalOutput.findNode(nameSuffix: String): ShortestPathObjectNode {
  val matching = mutableListOf<ShortestPathObjectNode>()
  fun visit(node: ShortestPathObjectNode) {
    if (node.name.endsWith(nameSuffix)) {
      matching += node
    }
    node.children.forEach { visit(it) }
  }
  visit(shortestPathTree)
  return matching.single()
}

/**
 * A static field holding an array of [wrapperCount] wrappers, each wrapping one of the 2
 * instances that another static field holds onto directly, so that those 2 instances stay
 * reachable without going through the growing array.
 */
internal fun HprofWriterHelper.arrayOfWrappersOfStablyHeldInstances(wrapperCount: Int) {
  val heldClassId = clazz("Held", fields = listOf("value" to ValueHolder.IntHolder::class))
  val wrapperClassId = clazz(
    "Wrapper",
    fields = listOf("held" to ValueHolder.ReferenceHolder::class)
  )
  val heldInstances = (1..2).map { value ->
    instance(heldClassId, listOf(ValueHolder.IntHolder(value)))
  }
  val wrappers = heldInstances.take(wrapperCount).map { instance(wrapperClassId, listOf(it)) }
  clazz(
    "ClassWithStatics",
    staticFields = listOf(
      "stable" to objectArray(*heldInstances.toTypedArray()),
      "growing" to objectArray(*wrappers.toTypedArray())
    )
  )
}

/**
 * [arrayCount] static fields, each holding an array of [sharedInstanceCount] wrappers, where the
 * wrapper at a given index in one array and the wrapper at that index in any other array all
 * reference the same shared instance.
 */
internal fun HprofWriterHelper.arraysOfWrappersSharingInstances(
  sharedInstanceCount: Int,
  arrayCount: Int = 2
) {
  val sharedClassId = clazz("Shared", fields = listOf("value" to ValueHolder.IntHolder::class))
  val wrapperClassId = clazz(
    "Wrapper",
    fields = listOf("shared" to ValueHolder.ReferenceHolder::class)
  )
  val sharedInstances = (1..sharedInstanceCount).map { value ->
    instance(sharedClassId, listOf(ValueHolder.IntHolder(value)))
  }
  val wrapperArray = {
    objectArray(*sharedInstances.map { instance(wrapperClassId, listOf(it)) }.toTypedArray())
  }
  clazz(
    "ClassWithStatics",
    staticFields = (1..arrayCount).map { arrayIndex -> "array$arrayIndex" to wrapperArray() }
  )
}

/**
 * A static field holding an array that references a shared array, and another static field
 * holding an array that references that same shared array plus an array of [growingArraySize]
 * instances. Both static fields reach the shared array through paths of the same length, so both
 * of the nodes that stand for those paths have it in the objects they were enqueued with, and
 * only the one that gets dequeued first visits it.
 */
internal fun HprofWriterHelper.arraysSharingAnArray(growingArraySize: Int) {
  val heldClassId = clazz("Held", fields = listOf("value" to ValueHolder.IntHolder::class))
  val held = { value: Int -> instance(heldClassId, listOf(ValueHolder.IntHolder(value))) }
  val sharedArray = objectArray(held(0))
  val growingArray = objectArray(*(1..growingArraySize).map { held(it) }.toTypedArray())
  clazz(
    "ClassWithStatics",
    staticFields = listOf(
      "stable" to objectArray(sharedArray),
      "growing" to objectArray(sharedArray, growingArray)
    )
  )
}

internal fun HprofWriterHelper.classWithStringsInStaticField(vararg strings: String) {
  clazz(
    "ClassWithStatics",
    staticFields = listOf("strings" to objectArray(*strings.map { string(it) }.toTypedArray()))
  )
}

internal fun nullReference() = ValueHolder.ReferenceHolder(NULL_REFERENCE)
