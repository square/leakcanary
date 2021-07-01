package shark.internal

import shark.HeapInstanceOutgoingRef
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapValue
import shark.MatchingInstanceExpander

/**
 * These classes is public so that it can be used from other modules, but it's not meant
 * to be a public API and will change without warning.
 */
object InternalSharedExpanderHelpers {

  fun expandHashMap(
    instance: HeapInstance,
    hashMapClassName: String,
    tableFieldName: String,
    nodeClassName: String,
    nodeNextFieldName: String,
    nodeKeyFieldName: String,
    nodeValueFieldName: String,
    keyName: String,
    keysOnly: Boolean,
  ): List<HeapInstanceOutgoingRef>? {
    val instanceClass = instance.instanceClass
    val table = instance[hashMapClassName, tableFieldName]!!.valueAsObjectArray
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

      val createKeyRef: (HeapValue) -> HeapInstanceOutgoingRef? = { key ->
        if (key.isNonNullReference) {
          HeapInstanceOutgoingRef(
            declaringClass = instanceClass,
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
          val valueRef =  if (value.isNonNullReference) {
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
        }.filterNotNull()
      }.toList()
    } else {
      null
    }
  }
}

class InternalSharedHashMapExpander(
  private val hashMapClassName: String,
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
    val table = instance[hashMapClassName, tableFieldName]!!.valueAsObjectArray
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
          val valueRef =  if (value.isNonNullReference) {
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