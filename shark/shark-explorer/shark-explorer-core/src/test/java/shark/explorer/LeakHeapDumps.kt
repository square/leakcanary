package shark.explorer

import java.io.File
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.ThreadObject
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

/**
 * The same two leaks, and a second way to the window that doesn't go through the activity, one step longer.
 *
 * Letting go of the activity here leaves the window exactly where it is, so the window is a leak of its own
 * and the chain for it is the way round rather than the shorter way through the activity — which is the
 * heap dump that says a chain avoids an object that shouldn't be in memory even at the cost of a step.
 */
internal fun TemporaryFolder.leakAlsoHeldAnotherWayHeapDump(): NestedLeaksHeapDump {
  val file = newFile("leak-also-held-another-way.hprof")
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
    // One step longer than the way through the activity, so that a chain taking it is a chain that gave up
    // a step for it: a way round of the same length would be picked between on a tie and prove nothing.
    val nearer = instance(
      clazz(className = "com.example.Nearer", fields = listOf("window" to ReferenceHolder::class)),
      fields = listOf(window)
    )
    val middle = instance(
      clazz(className = "com.example.Middle", fields = listOf("nearer" to ReferenceHolder::class)),
      fields = listOf(nearer)
    )
    val further = instance(
      clazz(className = "com.example.Further", fields = listOf("middle" to ReferenceHolder::class)),
      fields = listOf(middle)
    )
    gcRoot(JniGlobal(id = further.value, jniGlobalRefId = 1))
    activityObjectId = activity.value
    windowObjectId = window.value
  }
  return NestedLeaksHeapDump(file, activityObjectId, windowObjectId)
}

/**
 * A destroyed window that two destroyed activities hold, one of them a step further from its GC root.
 *
 * So there is no way to the window that avoids an object that shouldn't be in memory, and no one leaking
 * object it is behind either: neither activity dominates it, since letting go of one leaves it held by the
 * other. Which is what tells the two fold rules apart. A leak is a reference that shouldn't be there rather
 * than an object, and here there are two of those and the window is behind both: fix them and the window
 * goes with them, so there is nothing about it to write on a list that already says both.
 */
internal fun TemporaryFolder.leakTwoLeaksHoldHeapDump(): TwoLeaksHoldHeapDump {
  val file = newFile("leak-two-leaks-hold.hprof")
  var nearerActivityObjectId = 0L
  var windowObjectId = 0L
  file.dump {
    androidBuild(sdkInt = RECENT_SDK_INT)
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
    val nearerActivity = instance(activityClassId, fields = listOf(BooleanHolder(true), window))
    val holderClassId = clazz(
      className = HOLDER_CLASS_NAME,
      fields = listOf("activity" to ReferenceHolder::class)
    )
    val nearerHolder = instance(holderClassId, fields = listOf(nearerActivity))
    gcRoot(JniGlobal(id = nearerHolder.value, jniGlobalRefId = 0))
    val furtherActivity = instance(activityClassId, fields = listOf(BooleanHolder(true), window))
    val furtherHolder = instance(holderClassId, fields = listOf(furtherActivity))
    val furthest = instance(
      clazz(className = "com.example.Further", fields = listOf("holder" to ReferenceHolder::class)),
      fields = listOf(furtherHolder)
    )
    gcRoot(JniGlobal(id = furthest.value, jniGlobalRefId = 1))
    nearerActivityObjectId = nearerActivity.value
    windowObjectId = window.value
  }
  return TwoLeaksHoldHeapDump(file, nearerActivityObjectId, windowObjectId)
}

/** A [leakTwoLeaksHoldHeapDump] and the two of its three leaking objects the assertions are about. */
internal class TwoLeaksHoldHeapDump(
  val file: File,
  /** The one of the two activities whose GC root is nearer, which is the way the chain goes. */
  val nearerActivityObjectId: Long,
  /** The window both of them hold, which neither dominates and neither leaves reachable. */
  val windowObjectId: Long
)

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

/**
 * A heap dump where a destroyed activity is in a `java.util.ArrayList`, built of the fields the real class
 * has, since that is what Shark recognizes a list by.
 *
 * The point of it is the `Object[]` the list keeps its elements in: reading the reference the way it is
 * really stored puts two objects between the list and what it holds, and reading it the way you think
 * about the list gives `ArrayList[x]` — which is the difference between naming the same leak as LeakCanary
 * names it and naming it something else. See [DataStructureReferenceReader].
 */
internal fun TemporaryFolder.leakInAListHeapDump(): File {
  val file = newFile("leak-in-a-list.hprof")
  file.dump {
    androidBuild(sdkInt = RECENT_SDK_INT)
    val activity = instance(activityClass(), fields = listOf(BooleanHolder(true)))
    // Twelve slots with one element in them, like a list an app grew and took things out of: what is past
    // the size is not what the list holds, and it is the size that says so.
    val elementData = objectArray(
      arrayClass("java.lang.Object"),
      LongArray(LIST_CAPACITY).also { it[0] = activity.value }
    )
    val list = instance(
      clazz(
        className = "java.util.ArrayList",
        fields = listOf("elementData" to ReferenceHolder::class, "size" to IntHolder::class)
      ),
      fields = listOf(ReferenceHolder(elementData), IntHolder(1))
    )
    val holder = instance(
      clazz(className = HOLDER_CLASS_NAME, fields = listOf("list" to ReferenceHolder::class)),
      fields = listOf(list)
    )
    gcRoot(JniGlobal(id = holder.value, jniGlobalRefId = 0))
  }
  return file
}

/**
 * A heap dump where a destroyed activity is both on a thread's stack and in a field, one step from the
 * thread and two from anything else.
 *
 * A running method's frame is the shortest way to nearly anything an app is doing, and it is no answer to
 * "what is leaking this": the object is there because a method is running. So the field is the chain to
 * show, though it is the longer one, which is what [RootPathSearch] puts off a stack frame for.
 */
internal fun TemporaryFolder.leakOnAStackAndInAFieldHeapDump(): File {
  val file = newFile("leak-on-a-stack.hprof")
  file.dump {
    androidBuild(sdkInt = RECENT_SDK_INT)
    val thread = instance(
      clazz(className = "java.lang.Thread", fields = listOf("name" to ReferenceHolder::class)),
      fields = listOf(string("main"))
    )
    gcRoot(ThreadObject(id = thread.value, threadSerialNumber = 42, stackTraceSerialNumber = 0))
    val activity = instance(activityClass(), fields = listOf(BooleanHolder(true)))
    // A local variable of a method running on that thread, which is what a Java frame GC root is.
    gcRoot(JavaFrame(id = activity.value, threadSerialNumber = 42, frameNumber = 0))
    val holder = instance(
      clazz(className = HOLDER_CLASS_NAME, fields = listOf("activity" to ReferenceHolder::class)),
      fields = listOf(activity)
    )
    val owner = instance(
      clazz(className = "com.example.Owner", fields = listOf("holder" to ReferenceHolder::class)),
      fields = listOf(holder)
    )
    gcRoot(JniGlobal(id = owner.value, jniGlobalRefId = 0))
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

/** How many slots the list of [leakInAListHeapDump] has, of which it uses one. */
private const val LIST_CAPACITY = 12

/**
 * An Android version recent enough that none of Shark's known library leaks is one of these dumps, so that
 * what a chain through them is named after is the app's own references. See [androidBuild].
 */
private const val RECENT_SDK_INT = 34

/** The app framework's own `android.view.Window`, which is the class the window inspector looks for. */
internal const val WINDOW_CLASS_NAME = "com.android.internal.policy.PhoneWindow"

/** The pool `AndroidReferenceMatchers.TEXT_LINE__SCACHED` is about. */
internal const val TEXT_LINE_CLASS_NAME = "android.text.TextLine"

internal const val TEXT_LINE_CACHE_FIELD_NAME = "sCached"

/** That matcher applies up to API 22, so this is what the dump has to say it was taken on. */
private const val TEXT_LINE_LEAK_SDK_INT = 22
