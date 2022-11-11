package shark.internal

import shark.HeapGraph
import shark.HeapObject.HeapInstance
import shark.ValueHolder.ReferenceHolder
import shark.internal.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.internal.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader.OptionalFactory
import shark.internal.Reference.LazyDetails
import shark.internal.ReferenceLocationType.ARRAY_ENTRY
import shark.internal.ReferenceLocationType.INSTANCE_FIELD

internal enum class AndroidReferenceReaders : OptionalFactory {

  MESSAGE_QUEUE {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val messageQueueClass =
        graph.findClassByName("android.os.MessageQueue") ?: return null

      val messageQueueClassId = messageQueueClass.objectId

      return object : VirtualInstanceReferenceReader {
        override fun matches(instance: HeapInstance) =
          instance.instanceClassId == messageQueueClassId

        override fun read(source: HeapInstance): Sequence<Reference> {
          val firstMessage = source["android.os.MessageQueue", "mMessages"]!!.valueAsInstance
          return generateSequence(firstMessage) { node ->
            node["android.os.Message", "next"]!!.valueAsInstance
          }
            .withIndex()
            .map { (index, node) ->
              Reference(
                valueObjectId = node.objectId,
                isLowPriority = false,
                lazyDetailsResolver = {
                  LazyDetails(
                    name = "$index",
                    locationClassObjectId = messageQueueClassId,
                    locationType = ARRAY_ENTRY,
                    isVirtual = true,
                    matchedLibraryLeak = null
                  )
                }
              )
            }
        }
      }
    }
  },

  DRAWABLE_CALLBACK_WEAK_REF {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val drawableClass =
        graph.findClassByName("android.graphics.drawable.Drawable") ?: return null
      val drawableSubclassIds = drawableClass.subclasses.map { it.objectId }.toSet()

      val weakRefClassId =
        graph.findClassByName("java.lang.ref.WeakReference")?.objectId ?: return null

      return object : VirtualInstanceReferenceReader {
        override fun matches(instance: HeapInstance) =
          instance.instanceClassId in drawableSubclassIds

        override fun read(source: HeapInstance): Sequence<Reference> {
          val mCallback = source["android.graphics.drawable.Drawable", "mCallback"]?.valueAsInstance
            ?: return emptySequence()

          // https://cs.android.com/android/_/android/platform/frameworks/base/+/f2a47782f31b58d2d31bd00b50fe43604af8b9c2
          if (mCallback.instanceClassId != weakRefClassId) {
            return emptySequence()
          }

          val actualRef =
            mCallback["java.lang.ref.Reference", "referent"]!!.value.holder as ReferenceHolder

          return if (actualRef.isNull) {
            emptySequence()
          } else {
            sequenceOf(Reference(
              valueObjectId = actualRef.value,
              isLowPriority = true,
              lazyDetailsResolver = {
                LazyDetails(
                  name = "mCallback",
                  locationClassObjectId = source.instanceClassId,
                  locationType = INSTANCE_FIELD,
                  matchedLibraryLeak = null,
                  isVirtual = true
                )
              }
            ))
          }
        }
      }
    }
  },

  ANIMATOR_WEAK_REF_SUCKS {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val objectAnimatorClass =
        graph.findClassByName("android.animation.ObjectAnimator") ?: return null

      val weakRefClassId =
        graph.findClassByName("java.lang.ref.WeakReference")?.objectId ?: return null

      val objectAnimatorClassId = objectAnimatorClass.objectId

      return object : VirtualInstanceReferenceReader {
        override fun matches(instance: HeapInstance) =
          instance.instanceClassId == objectAnimatorClassId

        override fun read(source: HeapInstance): Sequence<Reference> {
          val mTarget = source["android.animation.ObjectAnimator", "mTarget"]?.valueAsInstance
            ?: return emptySequence()

          if (mTarget.instanceClassId != weakRefClassId) {
            return emptySequence()
          }

          val actualRef =
            mTarget["java.lang.ref.Reference", "referent"]!!.value.holder as ReferenceHolder

          return if (actualRef.isNull) {
            emptySequence()
          } else {
            sequenceOf(Reference(
              valueObjectId = actualRef.value,
              isLowPriority = true,
              lazyDetailsResolver = {
                LazyDetails(
                  name = "mTarget",
                  locationClassObjectId = objectAnimatorClassId,
                  locationType = INSTANCE_FIELD,
                  matchedLibraryLeak = null,
                  isVirtual = true
                )
              }
            ))
          }
        }
      }
    }
  },

  SAFE_ITERABLE_MAP {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val mapClass =
        graph.findClassByName(SAFE_ITERABLE_MAP_CLASS_NAME) ?: return null
      // A subclass of SafeIterableMap with dual storage in a backing HashMap for fast get.
      // Yes, that's a little weird.
      val fastMapClass = graph.findClassByName(FAST_SAFE_ITERABLE_MAP_CLASS_NAME)

      val mapClassId = mapClass.objectId
      val fastMapClassId = fastMapClass?.objectId

      return object : VirtualInstanceReferenceReader {
        override fun matches(instance: HeapInstance) =
          instance.instanceClassId.let { classId ->
            classId == mapClassId || classId == fastMapClassId
          }

        override fun read(source: HeapInstance): Sequence<Reference> {
          val start = source[SAFE_ITERABLE_MAP_CLASS_NAME, "mStart"]!!.valueAsInstance

          val entries = generateSequence(start) { node ->
            node[SAFE_ITERABLE_MAP_ENTRY_CLASS_NAME, "mNext"]!!.valueAsInstance
          }

          val locationClassObjectId = source.instanceClassId
          return entries.flatMap { entry ->
            // mkey is never null
            val key = entry[SAFE_ITERABLE_MAP_ENTRY_CLASS_NAME, "mKey"]!!.value

            val keyRef = Reference(
              valueObjectId = key.asObjectId!!,
              isLowPriority = false,
              lazyDetailsResolver = {
                LazyDetails(
                  name = "key()",
                  locationClassObjectId = locationClassObjectId,
                  locationType = ARRAY_ENTRY,
                  isVirtual = true,
                  matchedLibraryLeak = null
                )
              }
            )

            // mValue is never null
            val value = entry[SAFE_ITERABLE_MAP_ENTRY_CLASS_NAME, "mValue"]!!.value

            val valueRef = Reference(
              valueObjectId = value.asObjectId!!,
              isLowPriority = false,
              lazyDetailsResolver = {
                val keyAsString = key.asObject?.asInstance?.readAsJavaString()?.let { "\"$it\"" }
                val keyAsName =
                  keyAsString ?: key.asObject?.toString() ?: "null"
                LazyDetails(
                  name = keyAsName,
                  locationClassObjectId = locationClassObjectId,
                  locationType = ARRAY_ENTRY,
                  isVirtual = true,
                  matchedLibraryLeak = null
                )
              }
            )
            sequenceOf(keyRef, valueRef)
          }
        }
      }
    }
  },

  ARRAY_SET {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val arraySetClassId = graph.findClassByName(ARRAY_SET_CLASS_NAME)?.objectId ?: return null

      return object : VirtualInstanceReferenceReader {
        override fun matches(instance: HeapInstance) = instance.instanceClassId == arraySetClassId

        override fun read(source: HeapInstance): Sequence<Reference> {
          val mArray = source[ARRAY_SET_CLASS_NAME, "mArray"]!!.valueAsObjectArray!!
          val locationClassObjectId = source.instanceClassId
          return mArray.readElements()
            .filter { it.isNonNullReference }
            .map { reference ->
              Reference(
                valueObjectId = reference.asNonNullObjectId!!,
                isLowPriority = false,
                lazyDetailsResolver = {
                  LazyDetails(
                    name = "element()",
                    locationClassObjectId = locationClassObjectId,
                    locationType = ARRAY_ENTRY,
                    isVirtual = true,
                    matchedLibraryLeak = null,
                  )
                }
              )
            }
        }
      }
    }
  },

  ;

  companion object {
    private const val ARRAY_SET_CLASS_NAME = "android.util.ArraySet"

    // Note: not supporting the support lib version of these, which is identical but with an
    // "android" package prefix instead of "androidx".
    private const val SAFE_ITERABLE_MAP_CLASS_NAME = "androidx.arch.core.internal.SafeIterableMap"
    private const val FAST_SAFE_ITERABLE_MAP_CLASS_NAME =
      "androidx.arch.core.internal.FastSafeIterableMap"
    private const val SAFE_ITERABLE_MAP_ENTRY_CLASS_NAME =
      "androidx.arch.core.internal.SafeIterableMap\$Entry"
  }
}
