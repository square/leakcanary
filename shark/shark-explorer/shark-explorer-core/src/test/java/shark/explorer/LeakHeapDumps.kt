package shark.explorer

import java.io.File
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.HprofWriterHelper
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder
import shark.dump

/**
 * The heap dumps [HeapLeaksTest] opens, one per way an object ends up on the list of leaks.
 *
 * Out here rather than in that class for the same reason as [openTestHeapDump] and the dumps beside it: a
 * test file reads as what it asserts, and a dump per question is most of a file on its own.
 */

/**
 * A heap dump where the app handed an object to LeakCanary's `ObjectWatcher` and it is still there.
 *
 * The `KeyedWeakReference` the DSL writes is the one LeakCanary writes, durations and all, which is what
 * the row about a watched object shows.
 */
internal fun TemporaryFolder.watchedLeakHeapDump(): File {
  val file = newFile("watched-leak.hprof")
  file.dump {
    val leaking = WATCHED_CLASS_NAME watchedInstance {
      field["payload"] = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
    }
    val holder = "com.example.Holder" instance { field["presenter"] = leaking }
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
  }
  return file
}

/**
 * A heap dump with two destroyed activities in it, each held the same way, and a live one held that way
 * too.
 *
 * Nothing watched any of the three: a destroyed activity is a leak Shark's own inspectors recognize, which
 * is the half of the list that doesn't need LeakCanary to have been running. The two destroyed ones are
 * instances of one leak, since what holds them is the same field of the same class — the live one is what
 * says the inspectors are being read rather than every activity being listed.
 */
internal fun TemporaryFolder.destroyedActivitiesHeapDump(): File {
  val file = newFile("destroyed-activities.hprof")
  file.dump {
    val activityClassId = activityClass()
    val holderClassId = clazz(
      className = HOLDER_CLASS_NAME,
      fields = listOf("activity" to ReferenceHolder::class)
    )
    listOf(true, true, false).forEachIndexed { index, isDestroyed ->
      val activity = instance(activityClassId, fields = listOf(BooleanHolder(isDestroyed)))
      val holder = instance(holderClassId, fields = listOf(activity))
      gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = index.toLong()))
    }
  }
  return file
}

/**
 * A heap dump where a destroyed activity holds a destroyed window: two objects that shouldn't be in memory,
 * and one of them only still there because the other is.
 *
 * Which is what a real leak looks like — a destroyed activity holds its window, which holds its view tree,
 * and every one of those is an object an inspector recognizes — and so what the list has to fold into one
 * thing to fix.
 */
internal fun TemporaryFolder.nestedLeaksHeapDump(): NestedLeaksHeapDump {
  val file = newFile("nested-leaks.hprof")
  var activityObjectId = 0L
  var windowObjectId = 0L
  file.dump {
    val windowClassId = clazz(
      className = WINDOW_CLASS_NAME,
      superclassId = clazz(
        className = "android.view.Window",
        fields = listOf("mDestroyed" to BooleanHolder::class)
      )
    )
    val activityClassId = clazz(
      className = ACTIVITY_CLASS_NAME,
      superclassId = clazz(
        className = "android.app.Activity",
        fields = listOf("mDestroyed" to BooleanHolder::class, "mWindow" to ReferenceHolder::class)
      )
    )
    val window = instance(windowClassId, fields = listOf(BooleanHolder(true)))
    val activity = instance(activityClassId, fields = listOf(BooleanHolder(true), window))
    val holder = instance(
      clazz(className = HOLDER_CLASS_NAME, fields = listOf("activity" to ReferenceHolder::class)),
      fields = listOf(activity)
    )
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
    activityObjectId = activity.value
    windowObjectId = window.value
  }
  return NestedLeaksHeapDump(file, activityObjectId, windowObjectId)
}

/** A [nestedLeaksHeapDump] and the two leaking objects of it, since which one is listed is the question. */
internal class NestedLeaksHeapDump(
  val file: File,
  /** The one nearer the GC roots, which is the leak to fix. */
  val activityObjectId: Long,
  /** And the one it holds, which is on the chain drawn for the activity. */
  val windowObjectId: Long
)

/**
 * A heap dump where one field holds a whole array of objects that shouldn't be there: two destroyed
 * activities and a destroyed window, one per slot.
 *
 * Three objects, one thing to fix, and so one leak — which the two rules that say so are for. The slot an
 * object landed in is no part of what makes it that leak, since it changes from one heap dump of an app to
 * the next; neither is its class, since what a leak is, is the reference that shouldn't be holding.
 */
internal fun TemporaryFolder.leaksInOneArrayHeapDump(): File {
  val file = newFile("leaks-in-one-array.hprof")
  file.dump {
    val activityClassId = activityClass()
    val windowClassId = clazz(
      className = WINDOW_CLASS_NAME,
      superclassId = clazz(
        className = "android.view.Window",
        fields = listOf("mDestroyed" to BooleanHolder::class)
      )
    )
    val leaks = objectArray(
      instance(activityClassId, fields = listOf(BooleanHolder(true))),
      instance(activityClassId, fields = listOf(BooleanHolder(true))),
      instance(windowClassId, fields = listOf(BooleanHolder(true)))
    )
    val holder = instance(
      clazz(className = HOLDER_CLASS_NAME, fields = listOf(LEAK_ARRAY_FIELD_NAME to ReferenceHolder::class)),
      fields = listOf(leaks)
    )
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
  }
  return file
}

/** A heap dump where the only destroyed activity is one nothing points at any more. */
internal fun TemporaryFolder.collectedActivityHeapDump(): File {
  val file = newFile("collected-activity.hprof")
  file.dump {
    instance(activityClass(), fields = listOf(BooleanHolder(true)))
    val holder = "com.example.Holder" instance { }
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
  }
  return file
}

/**
 * A heap dump where a destroyed activity is held by a reference Shark knows leaks in code an app doesn't
 * control: `TextLine.sCached`, a pool the framework forgot to clear on the API levels this dump says it
 * was taken on.
 */
internal fun TemporaryFolder.libraryLeakHeapDump(): File {
  val file = newFile("library-leak.hprof")
  file.dump {
    androidBuild(sdkInt = TEXT_LINE_LEAK_SDK_INT)
    val activity = instance(activityClass(), fields = listOf(BooleanHolder(true)))
    TEXT_LINE_CLASS_NAME clazz { staticField[TEXT_LINE_CACHE_FIELD_NAME] = activity }
  }
  return file
}

/**
 * `android.app.Activity` and the app's own subclass of it, which is what the object inspectors look for:
 * an instance of the subclass whose inherited `mDestroyed` is true is a destroyed activity.
 *
 * Field values are written most derived class first, and the subclass declares none, so an instance of it
 * is written with the one field the superclass has.
 */
private fun HprofWriterHelper.activityClass(): Long = clazz(
  className = ACTIVITY_CLASS_NAME,
  superclassId = clazz(
    className = "android.app.Activity",
    fields = listOf("mDestroyed" to BooleanHolder::class)
  )
)

/**
 * What `android.os.Build` looks like in a dump, which is what Shark matches its library leak patterns
 * against: a dump without the three fields `AndroidBuildMirror` reads is one no known library leak can be
 * recognized in. See `ReferenceStrengthReader`.
 *
 * Duplicated from the app's tests rather than shared, since a test helper is not worth a module's public
 * API.
 */
private fun HprofWriterHelper.androidBuild(sdkInt: Int) {
  "android.os.Build" clazz {
    staticField["MANUFACTURER"] = string("Google")
    staticField["ID"] = string("BP31.250610.004")
  }
  "android.os.Build\$VERSION" clazz {
    staticField["SDK_INT"] = IntHolder(sdkInt)
  }
}

internal const val WATCHED_CLASS_NAME = "com.example.LeakingPresenter"

internal const val ACTIVITY_CLASS_NAME = "com.example.MainActivity"

internal const val HOLDER_CLASS_NAME = "com.example.Holder"

/** How a chain names the class a field is read on, which is how a leak named after that field is spelled. */
internal const val HOLDER_SIMPLE_CLASS_NAME = "Holder"

/** The field of [leaksInOneArrayHeapDump] that holds the array, which is what its leak is named after. */
internal const val LEAK_ARRAY_FIELD_NAME = "leaks"

/** The app framework's own `android.view.Window`, which is the class the window inspector looks for. */
internal const val WINDOW_CLASS_NAME = "com.android.internal.policy.PhoneWindow"

/** The pool `AndroidReferenceMatchers.TEXT_LINE__SCACHED` is about. */
internal const val TEXT_LINE_CLASS_NAME = "android.text.TextLine"

internal const val TEXT_LINE_CACHE_FIELD_NAME = "sCached"

/** That matcher applies up to API 22, so this is what the dump has to say it was taken on. */
private const val TEXT_LINE_LEAK_SDK_INT = 22
