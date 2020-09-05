package shark

import shark.GcRoot.JavaFrame
import shark.GcRoot.ThreadObject
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ReferenceHolder
import java.io.File

fun File.writeWeakReferenceCleared() {
  dump {
    keyedWeakReference(ReferenceHolder(0))
  }
}

fun File.writeNoPathToInstance() {
  dump {
    keyedWeakReference(instance(clazz("Leaking")))
  }
}

fun File.writeSinglePathToInstance() {
  dump {
    val leaking = instance(clazz("Leaking"))
    keyedWeakReference(leaking)
    clazz(
        "GcRoot", staticFields = listOf(
        "shortestPath" to leaking
    )
    )
  }
}

fun File.writeSinglePathToString(value: String = "Hi") {
  dump {
    val leaking = string(value)
    keyedWeakReference(leaking)
    clazz(
        "GcRoot", staticFields = listOf(
        "shortestPath" to leaking
    )
    )
  }
}

fun File.writeSinglePathsToCharArrays(values: List<String>) {
  dump {
    val arrays = mutableListOf<Long>()
    values.forEach {
      val leaking = it.charArrayDump
      keyedWeakReference(leaking)
      arrays.add(leaking.value)
    }
    clazz(
        className = "GcRoot",
        staticFields = listOf(
            "arrays" to ReferenceHolder(
                objectArray(clazz("char[][]"), arrays.toLongArray())
            )
        )
    )

  }
}

fun File.writeTwoPathsToInstance() {
  dump {
    val leaking = instance(clazz("Leaking"))
    keyedWeakReference(leaking)
    val hasLeaking = instance(
        clazz("HasLeaking", fields = listOf("leaking" to ReferenceHolder::class)),
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
  dump {
    val activityClassId = clazz(
        className = "android.app.Activity",
        fields = listOf("mDestroyed" to BooleanHolder::class)
    )
    val exampleActivityClassId = clazz(
        superclassId = activityClassId,
        className = "com.example.ExampleActivity"
    )
    val activityArrayClassId = arrayClass("com.example.ExampleActivity")

    val destroyedActivities = mutableListOf<ReferenceHolder>()
    for (i in 1..leakCount) {
      destroyedActivities.add(instance(exampleActivityClassId, listOf(BooleanHolder(true))))
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
      keyedWeakReference(instanceId)
    }
  }
}

fun File.writeJavaLocalLeak(
  threadClass: String,
  threadName: String
) {
  dump {
    val threadClassId =
      clazz(className = "java.lang.Thread", fields = listOf("name" to ReferenceHolder::class))
    val myThreadClassId = clazz(className = threadClass, superclassId = threadClassId)
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

fun File.writeTwoPathJavaLocalShorterLeak(
  threadClass: String,
  threadName: String
) {
  dump {
    val threadClassId =
      clazz(className = "java.lang.Thread", fields = listOf("name" to ReferenceHolder::class))
    val myThreadClassId = clazz(className = threadClass, superclassId = threadClassId)
    val threadInstance = instance(myThreadClassId, listOf(string(threadName)))
    gcRoot(
        ThreadObject(
            id = threadInstance.value, threadSerialNumber = 42, stackTraceSerialNumber = 0
        )
    )

    val leaking = "Leaking" watchedInstance {}
    gcRoot(JavaFrame(id = leaking.value, threadSerialNumber = 42, frameNumber = 0))

    val hasLeaking = instance(
        clazz("HasLeaking", fields = listOf("leaking" to ReferenceHolder::class)),
        fields = listOf(leaking)
    )
    clazz(
        "GcRoot", staticFields = listOf(
        "longestPath" to hasLeaking
    )
    )
  }
}