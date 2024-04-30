package shark

import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader.OptionalFactory
import shark.HeapObject.HeapInstance
import shark.internal.InternalSharedArrayListReferenceReader
import shark.internal.InternalSharedHashMapReferenceReader
import shark.internal.InternalSharedLinkedListReferenceReader
import shark.internal.InternalSharedWeakHashMapReferenceReader

/**
 * Defines [VirtualInstanceReferenceReader] factories for common OpenJDK data structures.
 *
 * Note: the expanders target the direct classes and don't target subclasses, as these might
 * include additional out going references that would be missed.
 */
enum class OpenJdkInstanceRefReaders : OptionalFactory {

  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/LinkedList.java
  LINKED_LIST {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val linkedListClass = graph.findClassByName("java.util.LinkedList") ?: return null
      val isOpenJdkImpl = linkedListClass.readRecordFields()
        .any { linkedListClass.instanceFieldName(it) == "first" }

      if (!isOpenJdkImpl) {
        return null
      }
      return InternalSharedLinkedListReferenceReader(
        classObjectId = linkedListClass.objectId,
        headFieldName = "first",
        nodeClassName = "java.util.LinkedList\$Node",
        nodeNextFieldName = "next",
        nodeElementFieldName = "item",
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/ArrayList.java
  ARRAY_LIST {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val arrayListClass = graph.findClassByName("java.util.ArrayList") ?: return null

      val isOpenJdkImpl = arrayListClass.readRecordFields()
        .any { arrayListClass.instanceFieldName(it) == "elementData" }

      if (!isOpenJdkImpl) {
        return null
      }

      return InternalSharedArrayListReferenceReader(
        className = "java.util.ArrayList",
        classObjectId = arrayListClass.objectId,
        elementArrayName = "elementData",
        sizeFieldName = "size",
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/concurrent/CopyOnWriteArrayList.java;bpv=0;bpt=1
  COPY_ON_WRITE_ARRAY_LIST {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val arrayListClass = graph.findClassByName("java.util.concurrent.CopyOnWriteArrayList") ?: return null

      val isOpenJdkImpl = arrayListClass.readRecordFields()
        .any { arrayListClass.instanceFieldName(it) == "array" }

      if (!isOpenJdkImpl) {
        return null
      }

      return InternalSharedArrayListReferenceReader(
        className = "java.util.concurrent.CopyOnWriteArrayList",
        classObjectId = arrayListClass.objectId,
        elementArrayName = "array",
        sizeFieldName = null,
      )
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
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val hashMapClass = graph.findClassByName("java.util.HashMap") ?: return null

      // No loadFactor field in the Apache Harmony impl.
      val isOpenJdkImpl = hashMapClass.readRecordFields()
        .any { hashMapClass.instanceFieldName(it) == "loadFactor" }

      if (!isOpenJdkImpl) {
        return null
      }

      val linkedHashMapClass = graph.findClassByName("java.util.LinkedHashMap")
      // Initially Entry, changed to Node in JDK 1.8
      val nodeClassName = if (graph.findClassByName("java.util.HashMap\$Entry") != null) {
        "java.util.HashMap\$Entry"
      } else if (graph.findClassByName("java.util.HashMap\$HashMapEntry") != null) {
        "java.util.HashMap\$HashMapEntry"
      } else {
        "java.util.HashMap\$Node"
      }

      val hashMapClassId = hashMapClass.objectId
      val linkedHashMapClassId = linkedHashMapClass?.objectId ?: 0

      return InternalSharedHashMapReferenceReader(
        className = "java.util.HashMap",
        tableFieldName = "table",
        nodeClassName = nodeClassName,
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

  // https://cs.android.com/android/platform/superproject/+/master:libcore/ojluni/src/main/java/java/util/concurrent/ConcurrentHashMap.java
  // Note: structure of impl shared by OpenJDK & Apache Harmony.
  CONCURRENT_HASH_MAP {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val hashMapClass =
        graph.findClassByName("java.util.concurrent.ConcurrentHashMap") ?: return null

      // No table field in Apache Harmony impl (as seen on Android 4).
      val isOpenJdkImpl = hashMapClass.readRecordFields()
        .any { hashMapClass.instanceFieldName(it) == "table" }

      if (!isOpenJdkImpl) {
        return null
      }

      val hashMapClassId = hashMapClass.objectId
      return InternalSharedHashMapReferenceReader(
        className = "java.util.concurrent.ConcurrentHashMap",
        tableFieldName = "table",
        nodeClassName = "java.util.concurrent.ConcurrentHashMap\$Node",
        nodeNextFieldName = "next",
        nodeKeyFieldName = "key",
        nodeValueFieldName = "val",
        keyName = "key()",
        keysOnly = false,
        matches = { it.instanceClassId == hashMapClassId },
        declaringClassId = { it.instanceClassId }
      )
    }
  },

  // https://cs.android.com/android/platform/superproject/main/+/main:libcore/ojluni/src/main/java/java/util/WeakHashMap.java
  WEAK_HASH_MAP {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val weakHashMapClass = graph.findClassByName("java.util.WeakHashMap") ?: return null

      // No table field in Apache Harmony impl.
      val isOpenJdkImpl = weakHashMapClass.readRecordFields()
        .any { weakHashMapClass.instanceFieldName(it) == "table" }

      if (!isOpenJdkImpl) {
        return null
      }

      val nullKeyObjectId = weakHashMapClass.readStaticField("NULL_KEY")!!.value.asObjectId!!

      return InternalSharedWeakHashMapReferenceReader(
        classObjectId = weakHashMapClass.objectId,
        tableFieldName = "table",
        isEntryWithNullKey = { entry ->
          val keyObjectId = entry["java.lang.ref.Reference", "referent"]!!.value.asObjectId!!
          keyObjectId == nullKeyObjectId
        },
      )
    }
  },

  /**
   * Handles HashSet & LinkedHashSet
   */
  HASH_SET {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val hashSetClass = graph.findClassByName("java.util.HashSet") ?: return null

      val isOpenJdkImpl = hashSetClass.readRecordFields()
        .any { hashSetClass.instanceFieldName(it) == "map" }

      if (!isOpenJdkImpl) {
        return null
      }

      val linkedHashSetClass = graph.findClassByName("java.util.LinkedHashSet")
      // Initially Entry, changed to Node in JDK 1.8
      val nodeClassName = if (graph.findClassByName("java.util.HashMap\$Entry") != null) {
        "java.util.HashMap\$Entry"
      } else if (graph.findClassByName("java.util.HashMap\$HashMapEntry") != null) {
        "java.util.HashMap\$HashMapEntry"
      } else {
        "java.util.HashMap\$Node"
      }
      val hashSetClassId = hashSetClass.objectId
      val linkedHashSetClassId = linkedHashSetClass?.objectId ?: 0
      return object : VirtualInstanceReferenceReader {
        override fun matches(instance: HeapInstance): Boolean {
          val instanceClassId = instance.instanceClassId
          return instanceClassId == hashSetClassId || instanceClassId == linkedHashSetClassId
        }

        override val readsCutSet = true

        override fun read(source: HeapInstance): Sequence<Reference> {
          // "HashSet.map" is never null when looking at the Android sources history, however
          // we've had a crash report where it was null on API 24.
          // https://github.com/square/leakcanary/issues/2342
          val map = source["java.util.HashSet", "map"]!!.valueAsInstance ?: return emptySequence()
          return InternalSharedHashMapReferenceReader(
            className = "java.util.HashMap",
            tableFieldName = "table",
            nodeClassName = nodeClassName,
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
