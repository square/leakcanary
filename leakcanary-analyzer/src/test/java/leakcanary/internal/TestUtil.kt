package leakcanary.internal

import leakcanary.Exclusion
import leakcanary.Exclusion.ExclusionType.InstanceFieldExclusion
import leakcanary.Exclusion.ExclusionType.ThreadExclusion
import leakcanary.Exclusion.Status.NEVER_REACHABLE
import leakcanary.Exclusion.Status.WEAKLY_REACHABLE
import leakcanary.HeapValue.BooleanValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HprofParser
import leakcanary.HprofWriter
import leakcanary.KeyedWeakReference
import java.io.File
import java.lang.ref.PhantomReference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference

fun File.dumpMultipleActivityLeaks(leakCount: Int) {
  HprofWriter.open(this)
      .helper {
        val activityClassId = clazz(
            className = "android.app.Activity",
            fields = listOf("mDestroyed" to BooleanValue::class)
        )
        val exampleActivityClassId = clazz(
            superClassId = activityClassId,
            className = "com.example.ExampleActivity"
        )
        val activityArrayClassId = arrayClass("com.example.ExampleActivity")

        val destroyedActivities = mutableListOf<Long>()
        for (i in 1..leakCount) {
          destroyedActivities.add(instance(exampleActivityClassId, listOf(BooleanValue(true))))
        }

        clazz(
            className = "com.example.ActivityHolder",
            staticFields = listOf(
                "activities" to ObjectReference(
                    objectArray(activityArrayClassId, destroyedActivities.toLongArray())
                )
            )
        )
        destroyedActivities.forEach { instanceId ->
          keyedWeakReference("com.example.ExampleActivity", instanceId)
        }
      }
}

val defaultExclusionFactory: (HprofParser) -> List<Exclusion> = {
  listOf(
      Exclusion(
          type = InstanceFieldExclusion(WeakReference::class.java.name, "referent"),
          status = WEAKLY_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion(KeyedWeakReference::class.java.name, "referent"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion(SoftReference::class.java.name, "referent"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion(PhantomReference::class.java.name, "referent"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.Finalizer", "prev"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.Finalizer", "element"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.Finalizer", "next"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.FinalizerReference", "prev"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.FinalizerReference", "element"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("java.lang.ref.FinalizerReference", "next"),
          status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("sun.misc.Cleaner", "prev"), status = NEVER_REACHABLE
      )
      ,
      Exclusion(
          type = InstanceFieldExclusion("sun.misc.Cleaner", "next"), status = NEVER_REACHABLE
      )
      ,

      Exclusion(
          type = ThreadExclusion("FinalizerWatchdogDaemon"),
          status = NEVER_REACHABLE
      ),
      Exclusion(
          type = ThreadExclusion("main"),
          status = NEVER_REACHABLE
      )
  )
}