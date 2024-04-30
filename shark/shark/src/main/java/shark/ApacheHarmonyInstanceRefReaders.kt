package shark

import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader.OptionalFactory
import shark.HeapObject.HeapInstance
import shark.internal.InternalSharedArrayListReferenceReader
import shark.internal.InternalSharedHashMapReferenceReader
import shark.internal.InternalSharedLinkedListReferenceReader
import shark.internal.InternalSharedWeakHashMapReferenceReader

/**
 * Defines [VirtualInstanceReferenceReader] factories for common Apache Harmony data structures.
 *
 * Note: the expanders target the direct classes and don't target subclasses, as these might
 * include additional out going references that would be missed.
 */
enum class ApacheHarmonyInstanceRefReaders : OptionalFactory {

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/LinkedList.java
  LINKED_LIST {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val linkedListClass = graph.findClassByName("java.util.LinkedList") ?: return null
      val isApacheHarmonyImpl = linkedListClass.readRecordFields()
        .any { linkedListClass.instanceFieldName(it) == "voidLink" }

      if (!isApacheHarmonyImpl) {
        return null
      }
      return InternalSharedLinkedListReferenceReader(
        classObjectId = linkedListClass.objectId,
        headFieldName = "voidLink",
        nodeClassName = "java.util.LinkedList\$Link",
        nodeNextFieldName = "next",
        nodeElementFieldName = "data",
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/ArrayList.java
  ARRAY_LIST {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val arrayListClass = graph.findClassByName("java.util.ArrayList") ?: return null

      val isApacheHarmonyImpl = arrayListClass.readRecordFields()
        .any { arrayListClass.instanceFieldName(it) == "array" }

      if (!isApacheHarmonyImpl) {
        return null
      }

      return InternalSharedArrayListReferenceReader(
        className = "java.util.ArrayList",
        classObjectId = arrayListClass.objectId,
        elementArrayName = "array",
        sizeFieldName = "size",
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/concurrent/CopyOnWriteArrayList.java
  COPY_ON_WRITE_ARRAY_LIST {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val arrayListClass =
        graph.findClassByName("java.util.concurrent.CopyOnWriteArrayList") ?: return null

      val isApacheHarmonyImpl = arrayListClass.readRecordFields()
        .any { arrayListClass.instanceFieldName(it) == "elements" }

      if (!isApacheHarmonyImpl) {
        return null
      }

      return InternalSharedArrayListReferenceReader(
        className = "java.util.concurrent.CopyOnWriteArrayList",
        classObjectId = arrayListClass.objectId,
        elementArrayName = "elements",
        sizeFieldName = null,
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/HashMap.java
  /**
   * Handles HashMap & LinkedHashMap
   */
  HASH_MAP {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val hashMapClass = graph.findClassByName("java.util.HashMap") ?: return null

      // No loadFactor field in the Apache Harmony impl.
      val isOpenJdkImpl = hashMapClass.readRecordFields()
        .any { hashMapClass.instanceFieldName(it) == "loadFactor" }

      if (isOpenJdkImpl) {
        return null
      }
      val linkedHashMapClass = graph.findClassByName("java.util.LinkedHashMap")

      val hashMapClassId = hashMapClass.objectId
      val linkedHashMapClassId = linkedHashMapClass?.objectId ?: 0

      return InternalSharedHashMapReferenceReader(
        className = "java.util.HashMap",
        tableFieldName = "table",
        nodeClassName = "java.util.HashMap\$HashMapEntry",
        nodeNextFieldName = "next",
        nodeKeyFieldName = "key",
        nodeValueFieldName = "value",
        keyName = "key()",
        keysOnly = false,
        matches = {
          val instanceClassId = it.instanceClassId
          instanceClassId == hashMapClassId || instanceClassId == linkedHashMapClassId
        },
        declaringClassId = { it.instanceClassId }
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/WeakHashMap.java
  WEAK_HASH_MAP {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val weakHashMapClass = graph.findClassByName("java.util.WeakHashMap") ?: return null

      // No table field in Apache Harmony impl.
      val isOpenJdkImpl = weakHashMapClass.readRecordFields()
        .any { weakHashMapClass.instanceFieldName(it) == "table" }

      if (isOpenJdkImpl) {
        return null
      }

      return InternalSharedWeakHashMapReferenceReader(
        classObjectId = weakHashMapClass.objectId,
        tableFieldName = "elementData",
        isEntryWithNullKey = { entry ->
          entry["java.util.WeakHashMap\$Entry", "isNull"]!!.value.asBoolean == true
        },
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/android-6.0.1_r81:libcore/luni/src/main/java/java/util/HashSet.java
  /**
   * Handles HashSet & LinkedHashSet
   */
  HASH_SET {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val hashSetClass = graph.findClassByName("java.util.HashSet") ?: return null

      val isApacheHarmonyImpl = hashSetClass.readRecordFields()
        .any { hashSetClass.instanceFieldName(it) == "backingMap" }

      if (!isApacheHarmonyImpl) {
        return null
      }

      val linkedHashSetClass = graph.findClassByName("java.util.LinkedHashSet")
      val hashSetClassId = hashSetClass.objectId
      val linkedHashSetClassId = linkedHashSetClass?.objectId ?: 0
      return object : VirtualInstanceReferenceReader {

        override fun matches(instance: HeapInstance): Boolean {
          val instanceClassId = instance.instanceClassId
          return (instanceClassId == hashSetClassId || instanceClassId == linkedHashSetClassId)
        }

        override val readsCutSet = true

        override fun read(source: HeapInstance): Sequence<Reference> {
          // "HashSet.backingMap" is never null.
          val map = source["java.util.HashSet", "backingMap"]!!.valueAsInstance!!
          return InternalSharedHashMapReferenceReader(
            className = "java.util.HashMap",
            tableFieldName = "table",
            nodeClassName = "java.util.HashMap\$HashMapEntry",
            nodeNextFieldName = "next",
            nodeKeyFieldName = "key",
            nodeValueFieldName = "value",
            keyName = "element()",
            keysOnly = true,
            matches = { true },
            declaringClassId = { source.instanceClassId }
          ).read(map)
        }
      }
    }
  }

  ;
}
