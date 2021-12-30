package shark.internal

import shark.ChainingInstanceExpander.SyntheticInstanceExpander
import shark.HeapInstanceRef
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapValue

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
) : SyntheticInstanceExpander {
  override fun matches(instance: HeapInstance): Boolean {
    return matches.invoke(instance)
  }

  override fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceRef> {
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

      val createKeyRef: (HeapValue) -> HeapInstanceRef? = { key ->
        if (key.isNonNullReference) {
          HeapInstanceRef(
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
            HeapInstanceRef(
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
      emptyList()
    }
  }
}

class InternalSharedArrayListExpander(
  private val className: String,
  private val classObjectId: Long,
  private val elementArrayName: String,
  private val sizeFieldName: String?
) : SyntheticInstanceExpander {

  override fun matches(instance: HeapInstance): Boolean {
    return instance.instanceClassId == classObjectId
  }

  override fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceRef> {
    val instanceClass = instance.instanceClass
    val elementFieldRef =
      instance[className, elementArrayName]!!.valueAsObjectArray ?: return emptyList()

    val elements = if (sizeFieldName != null) {
      val size = instance[className, sizeFieldName]!!.value.asInt!!
      elementFieldRef.readElements().take(size)
    } else {
      elementFieldRef.readElements()
    }
    return elements.withIndex()
      .mapNotNull { (index, elementValue) ->
        if (elementValue.isNonNullReference) {
          HeapInstanceRef(
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
) : SyntheticInstanceExpander {

  override fun matches(instance: HeapInstance): Boolean {
    return instance.instanceClassId == classObjectId
  }

  override fun expandOutgoingRefs(instance: HeapInstance): List<HeapInstanceRef> {
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
          HeapInstanceRef(
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
