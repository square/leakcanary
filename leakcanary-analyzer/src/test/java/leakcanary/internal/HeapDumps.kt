package leakcanary.internal

import leakcanary.HeapValue.BooleanValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HprofWriter
import java.io.File

fun File.writeWeakReferenceCleared() {
  HprofWriter.open(this)
      .helper {
        keyedWeakReference("Leaking", 0)
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
        val leaking = instance(
            clazz("Leaking")
        )
        keyedWeakReference("Leaking", leaking)
        clazz(
            "GcRoot", staticFields = listOf(
            "shortestPath" to ObjectReference(leaking)
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
            "shortestPath" to ObjectReference(leaking)
        )
        )
      }
}

fun File.writeSinglePathsToCharArrays(values: List<CharArray>) {
  HprofWriter.open(this)
      .helper {
        val arrays = mutableListOf<Long>()
        values.forEach {
          val leaking = array(it)
          keyedWeakReference("char[]", leaking)
          arrays.add(leaking)
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

fun File.writeCustomPathToInstance(path: List<Pair<String, String>>) {
  HprofWriter.open(this)
      .helper {
        val leaking = instance(
            clazz("Leaking")
        )
        keyedWeakReference("Leaking", leaking)
        var previousInstance = leaking
        for (index in path.lastIndex downTo 1) {
          val (className, fieldName) = path[index]
          previousInstance = instance(
              clazz(className, fields = listOf(fieldName to ObjectReference::class)),
              fields = listOf(ObjectReference(previousInstance))
          )
        }
        val root = path.first()
        clazz(
            root.first, staticFields = listOf(
            root.second to ObjectReference(previousInstance)
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
            fields = listOf(ObjectReference(leaking))
        )
        clazz(
            "GcRoot", staticFields = listOf(
            "shortestPath" to ObjectReference(leaking),
            "longestPath" to ObjectReference(hasLeaking)
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