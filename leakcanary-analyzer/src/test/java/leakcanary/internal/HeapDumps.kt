package leakcanary.internal

import leakcanary.GcRoot.JavaFrame
import leakcanary.GcRoot.ThreadObject
import leakcanary.HeapValue.BooleanValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HprofWriter
import java.io.File

fun File.writeWeakReferenceCleared() {
  HprofWriter.open(this)
      .helper {
        keyedWeakReference("Leaking", ObjectReference(0))
      }
}

fun File.writeNoPathToInstance() {
  HprofWriter.open(this)
      .helper {
        keyedWeakReference("Leaking", instance(clazz("Leaking")))
      }
}

fun File.writeSinglePathToInstance() {
  HprofWriter.open(this)
      .helper {
        val leaking = instance(clazz("Leaking"))
        keyedWeakReference("Leaking", leaking)
        clazz(
            "GcRoot", staticFields = listOf(
            "shortestPath" to leaking
        )
        )
      }
}

fun File.writeSinglePathToString(value: String = "Hi") {
  HprofWriter.open(this)
      .helper {
        val leaking = string(value)
        keyedWeakReference("java.lang.String", leaking)
        clazz(
            "GcRoot", staticFields = listOf(
            "shortestPath" to leaking
        )
        )
      }
}

fun File.writeSinglePathsToCharArrays(values: List<String>) {
  HprofWriter.open(this)
      .helper {
        val arrays = mutableListOf<Long>()
        values.forEach {
          val leaking = it.charArrayDump
          keyedWeakReference("char[]", leaking)
          arrays.add(leaking.value)
        }
        clazz(
            className = "GcRoot",
            staticFields = listOf(
                "arrays" to ObjectReference(
                    objectArray(clazz("char[][]"), arrays.toLongArray())
                )
            )
        )

      }
}

fun File.writeTwoPathsToInstance() {
  HprofWriter.open(this)
      .helper {
        val leaking = instance(clazz("Leaking"))
        keyedWeakReference("Leaking", leaking)
        val hasLeaking = instance(
            clazz("HasLeaking", fields = listOf("leaking" to ObjectReference::class)),
            fields = listOf(leaking)
        )
        clazz(
            "GcRoot", staticFields = listOf(
            "shortestPath" to leaking,
            "longestPath" to hasLeaking
        )
        )
      }
}

fun File.writeMultipleActivityLeaks(leakCount: Int) {
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

        val destroyedActivities = mutableListOf<ObjectReference>()
        for (i in 1..leakCount) {
          destroyedActivities.add(instance(exampleActivityClassId, listOf(BooleanValue(true))))
        }

        clazz(
            className = "com.example.ActivityHolder",
            staticFields = listOf(
                "activities" to
                    objectArrayOf(
                        activityArrayClassId, *destroyedActivities.toTypedArray()
                    )
            )
        )
        destroyedActivities.forEach { instanceId ->
          keyedWeakReference("com.example.ExampleActivity", instanceId)
        }
      }
}

fun File.writeJavaLocalLeak(
  threadClass: String,
  threadName: String
) {
  dump {
    val threadClassId =
      clazz(className = "java.lang.Thread", fields = listOf("name" to ObjectReference::class))
    val myThreadClassId = clazz(className = threadClass, superClassId = threadClassId)
    val threadInstance = instance(myThreadClassId, listOf(string(threadName)))
    gcRoot(
        ThreadObject(
            id = threadInstance.value, threadSerialNumber = 42, stackTraceSerialNumber = 0
        )
    )

    val leaking = "Leaking" watchedInstance {}
    gcRoot(JavaFrame(id = leaking.value, threadSerialNumber = 42, frameNumber = 0))
  }
}