package shark.internal

import shark.HeapInstanceOutgoingRef
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapValue
import shark.MatchingInstanceExpander

// These classes are public so that it can be used from other modules, but they're not meant
// to be a public API and will change without warning.

class InternalSharedHashMapExpander(
  private val className: String,
  private val tableFieldName: String,
  private val nodeClassName: String,
  private val nodeNextFieldName: String,
  private val nodeKeyFieldName: String,
  private val nodeValueFieldName: String,
  private val keyName: String,
  private val keysOnly: Boolean,
  private val matches: (HeapInstance) -> Boolean,
  private val declaringClass: (HeapInstance) -> (HeapClass)
) : MatchingInstanceExpander {

  override fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceOutgoingRef>? {
    if (!matches(instance)) {
      return null
    }
    val table = instance[className, tableFieldName]!!.valueAsObjectArray
    return if (table != null) {
      val entries = table.readElements().mapNotNull { entryRef ->
        if (entryRef.isNonNullReference) {
          val entry = entryRef.asObject!!.asInstance!!
          generateSequence(entry) { node ->
            node[nodeClassName, nodeNextFieldName]!!.valueAsInstance
          }
        } else {
          null
        }
      }.flatten()

      val declaringClass = declaringClass(instance)

      val createKeyRef: (HeapValue) -> HeapInstanceOutgoingRef? = { key ->
        if (key.isNonNullReference) {
          HeapInstanceOutgoingRef(
            declaringClass = declaringClass,
            // All entries are represented by the same key name, e.g. "key()"
            name = keyName,
            objectId = key.asObjectId!!,
            isArrayLike = true
          )
        } else null
      }

      if (keysOnly) {
        entries.mapNotNull { entry ->
          val key = entry[nodeClassName, nodeKeyFieldName]!!.value
          createKeyRef(key)
        }
      } else {
        entries.flatMap { entry ->
          val key = entry[nodeClassName, nodeKeyFieldName]!!.value
          val keyRef = createKeyRef(key)

          val value = entry[nodeClassName, nodeValueFieldName]!!.value
          val valueRef = if (value.isNonNullReference) {
            val keyAsName =
              key.asObject?.asInstance?.readAsJavaString() ?: key.asObject?.toString() ?: "null"
            HeapInstanceOutgoingRef(
              declaringClass = declaringClass,
              name = keyAsName,
              objectId = value.asObjectId!!,
              isArrayLike = true
            )
          } else null
          sequenceOf(keyRef, valueRef)
        }.filterNotNull()
      }.toList()
    } else {
      null
    }
  }
}

class InternalSharedArrayListExpander(
  private val className: String,
  private val classObjectId: Long,
  private val elementArrayName: String,
  private val sizeFieldName: String?
) : MatchingInstanceExpander {
  override fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceOutgoingRef>? {
    if (instance.instanceClassId != classObjectId) {
      return null
    }

    val instanceClass = instance.instanceClass
    val elementFieldRef =
      instance[className, elementArrayName]!!.valueAsObjectArray ?: return null

    val elements = if (sizeFieldName != null) {
      val size = instance[className, sizeFieldName]!!.value.asInt!!
      elementFieldRef.readElements().take(size)
    } else {
      elementFieldRef.readElements()
    }
    return elements.withIndex()
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
  }
}

class InternalSharedLinkedListExpander(
  private val classObjectId: Long,
  private val headFieldName: String,
  private val nodeClassName: String,
  private val nodeNextFieldName: String,
  private val nodeElementFieldName: String
) : MatchingInstanceExpander {
  override fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceOutgoingRef>? {
    if (instance.instanceClassId != classObjectId) {
      return null
    }
    val instanceClass = instance.instanceClass
    // head may be null, in that case we generate an empty sequence.
    val firstNode = instance["java.util.LinkedList", headFieldName]!!.valueAsInstance
    return generateSequence(firstNode) { node ->
      node[nodeClassName, nodeNextFieldName]!!.valueAsInstance
    }
      .withIndex()
      .mapNotNull { (index, node) ->
        val itemObjectId = node[nodeClassName, nodeElementFieldName]!!.value.asObjectId
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
  }
}