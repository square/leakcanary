package shark

import shark.HeapObject.HeapInstance
import shark.LibraryLeakReferenceMatcher
import shark.ReferencePattern.InstanceFieldPattern
import shark.ValueHolder
import shark.ValueHolder.ReferenceHolder
import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader
import shark.ChainingInstanceReferenceReader.VirtualInstanceReferenceReader.OptionalFactory
import shark.Reference.LazyDetails
import shark.ReferenceLocationType.ARRAY_ENTRY
import shark.ReferenceLocationType.INSTANCE_FIELD
import shark.ReferencePattern.Companion
import shark.ReferencePattern.Companion.instanceField

enum class AndroidReferenceReaders : OptionalFactory {

  /**
   * ActivityThread.mNewActivity is a linked list of ActivityClientRecord that keeps track of
   * activities after they were resumed, until the main thread is idle. This is used to report
   * analytics to system_server about how long it took for the main thread to settle after
   * resuming an activity. Unfortunately, if the main thread never becomes idle, all these
   * new activities leak in memory.
   *
   * We'd normally catch these with a pattern in AndroidReferenceMatchers, and we do have
   * AndroidReferenceMatchers.ACTIVITY_THREAD__M_NEW_ACTIVITIES to do that, however this matching
   * only works if none of the activities alive are waiting for idle. If any activity alive is
   * still waiting for idle (which all the alive activities would be if they main thread is never
   * idle) then ActivityThread.mActivities will reference an ActivityClientRecord through an
   * ArrayMap and because ActivityClientRecord are reused that instance will also have its nextIdle
   * fields set, so we're effectively traversing the ActivityThread.mNewActivity from a completely
   * different and unexpected entry point.
   *
   * To fix that problem of broken pattern matching, we emit the mNewActivities field when
   * finding an ActivityThread instance, and because custom ref readers have priority over the
   * default instance field reader, we're guaranteed that mNewActivities is enqueued before
   * mActivities. Unfortunately, that also means we can't rely on AndroidReferenceMatchers as
   * those aren't used here, so we recreate our own LibraryLeakReferenceMatcher.
   *
   * We want to traverse mNewActivities before mActivities so we can't set isLowPriority to true
   * like we would for normal path tagged as source of leak. So we will prioritize going through all
   * activities in mNewActivities, some of which aren't destroyed yet (and therefore not leaking).
   * Going through those paths of non leaking activities, we might find other leaks though.
   * This would result in us tagging unrelated leaks as part of the mNewActivities leak. To prevent
   * this, we traverse ActivityThread.mNewActivities as a linked list through
   * ActivityClientRecord.nextIdle as a linked list, but we emit only ActivityClientRecord.activity
   * fields if such activities are destroyed, which means any live activity in
   * ActivityThread.mNewActivities will be discovered through the normal field navigation process
   * and should go through ActivityThread.mActivities.
   */
  ACTIVITY_THREAD__NEW_ACTIVITIES {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val activityThreadClass = graph.findClassByName("android.app.ActivityThread") ?: return null

      if (activityThreadClass.readRecordFields().none {
          activityThreadClass.instanceFieldName(it) == "mNewActivities"
        }
      ) {
        return null
      }

      val activityClientRecordClass =
        graph.findClassByName("android.app.ActivityThread\$ActivityClientRecord") ?: return null

      val activityClientRecordFieldNames = activityClientRecordClass.readRecordFields()
        .map { activityThreadClass.instanceFieldName(it) }
        .toList()

      if ("nextIdle" !in activityClientRecordFieldNames ||
        "activity" !in activityClientRecordFieldNames
      ) {
        return null
      }

      val activityThreadClassId = activityThreadClass.objectId
      val activityClientRecordClassId = activityClientRecordClass.objectId

      return object : VirtualInstanceReferenceReader {
        override fun matches(instance: HeapInstance) =
          instance.instanceClassId == activityThreadClassId ||
            instance.instanceClassId == activityClientRecordClassId

        override val readsCutSet = false

        override fun read(source: HeapInstance): Sequence<Reference> {
          return if (source.instanceClassId == activityThreadClassId) {
            val mNewActivities =
              source["android.app.ActivityThread", "mNewActivities"]!!.value.asObjectId!!
            if (mNewActivities == ValueHolder.NULL_REFERENCE) {
              emptySequence()
            } else {
              source.graph.context[ACTIVITY_THREAD__NEW_ACTIVITIES.name] = mNewActivities
              sequenceOf(
                Reference(
                  valueObjectId = mNewActivities,
                  isLowPriority = false,
                  lazyDetailsResolver = {
                    LazyDetails(
                      name = "mNewActivities",
                      locationClassObjectId = activityThreadClassId,
                      locationType = INSTANCE_FIELD,
                      isVirtual = false,
                      matchedLibraryLeak = instanceField(
                        className = "android.app.ActivityThread",
                        fieldName = "mNewActivities"
                      ).leak(
                        description = """
                       New activities are leaked by ActivityThread until the main thread becomes idle.
                       Tracked here: https://issuetracker.google.com/issues/258390457
                     """.trimIndent()
                      )
                    )
                  })
              )
            }
          } else {
            val mNewActivities =
              source.graph.context.get<Long?>(ACTIVITY_THREAD__NEW_ACTIVITIES.name)
            if (mNewActivities == null || source.objectId != mNewActivities) {
              emptySequence()
            } else {
              generateSequence(source) { node ->
                node["android.app.ActivityThread\$ActivityClientRecord", "nextIdle"]!!.valueAsInstance
              }.withIndex().mapNotNull { (index, node) ->

                val activity =
                  node["android.app.ActivityThread\$ActivityClientRecord", "activity"]!!.valueAsInstance
                if (activity == null ||
                  // Skip non destroyed activities.
                  // (!= true because we also skip if mDestroyed is missing)
                  activity["android.app.Activity", "mDestroyed"]?.value?.asBoolean != true
                ) {
                  null
                } else {
                  Reference(
                    valueObjectId = activity.objectId,
                    isLowPriority = false,
                    lazyDetailsResolver = {
                      LazyDetails(
                        name = "$index",
                        locationClassObjectId = activityClientRecordClassId,
                        locationType = ARRAY_ENTRY,
                        isVirtual = true,
                        matchedLibraryLeak = null
                      )
                    })
                }
              }
            }
          }
        }
      }
    }
  },

  MESSAGE_QUEUE {
    override fun create(graph: HeapGraph): VirtualInstanceReferenceReader? {
      val messageQueueClass =
        graph.findClassByName("android.os.MessageQueue") ?: return null

      val messageQueueClassId = messageQueueClass.objectId

      return object : VirtualInstanceReferenceReader {
        override fun matches(instance: HeapInstance) =
          instance.instanceClassId == messageQueueClassId

        override val readsCutSet = false

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

        override val readsCutSet = false

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

        override val readsCutSet = true

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

        override val readsCutSet = true

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
