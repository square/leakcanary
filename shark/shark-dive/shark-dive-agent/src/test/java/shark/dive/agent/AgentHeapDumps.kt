package shark.dive.agent

import java.io.Closeable
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.HprofWriterHelper
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.dive.HeapDive

/**
 * The heap dump the tools of this module are asked about, and the addresses of the three objects in it.
 *
 * Shaped like the leak the method is about, which takes three objects and not two: one an inspector knows
 * belongs in memory, one it knows shouldn't be there, and one in between that nothing in the heap dump can
 * say either way about. That middle object is the whole point — it is what makes `conclude` refuse until
 * somebody has read the code and recorded what they found, and a two object dump would name its faulty
 * reference with nobody having investigated anything.
 */
internal fun TemporaryFolder.applicationHoldsActivityThroughHolder(): InvestigationHeapDump {
  val file = newFile("application-holds-activity-through-holder.hprof")
  var applicationObjectId = 0L
  var holderObjectId = 0L
  var activityObjectId = 0L
  file.dump {
    androidBuild()
    val activity = instance(activityClass(), fields = listOf(BooleanHolder(true)))
    val holder = HOLDER_CLASS_NAME instance { field["activity"] = activity }
    val application = instance(
      clazz(
        className = APPLICATION_CLASS_NAME,
        superclassId = clazz(className = "android.app.Application"),
        fields = listOf(HOLDER_FIELD_NAME to ReferenceHolder::class)
      ),
      fields = listOf(holder)
    )
    gcRoot(JniGlobal(id = application.value, jniGlobalRefId = 0))
    applicationObjectId = application.value
    holderObjectId = holder.value
    activityObjectId = activity.value
  }
  return InvestigationHeapDump(
    dive = HeapDive.open(file),
    applicationObjectId = applicationObjectId,
    holderObjectId = holderObjectId,
    activityObjectId = activityObjectId
  )
}

/**
 * An open heap dump and the addresses a test names its objects by.
 *
 * The addresses come from the fixture rather than from a search of the dump so that a test that fails is a
 * test about the tool it names: finding the activity by class name first would make every one of them also a
 * test of `find_objects`.
 */
internal class InvestigationHeapDump(
  val dive: HeapDive,
  /** The app's own `Application`, which an inspector marks as belonging in memory. */
  val applicationObjectId: Long,
  /** The object in between, which nothing in the heap dump knows anything about. */
  val holderObjectId: Long,
  /** The destroyed activity, which an inspector marks as one that should be gone. */
  val activityObjectId: Long
) : Closeable {

  override fun close() {
    dive.close()
  }
}

/**
 * `android.app.Activity` and the app's own subclass of it, which is what the object inspectors look for: an
 * instance of the subclass whose inherited `mDestroyed` is true is a destroyed activity.
 *
 * Field values are written most derived class first, and the subclass declares none, so an instance of it is
 * written with the one field the superclass has.
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
 * against — and a dump with the class but not these three fields makes it throw a bare NPE from under
 * everything. See `shark/shark-dive/AGENTS.md`.
 *
 * Duplicated from the other modules' tests rather than shared, since a test helper is not worth a module's
 * public API.
 */
private fun HprofWriterHelper.androidBuild() {
  "android.os.Build" clazz {
    staticField["MANUFACTURER"] = string("Google")
    staticField["ID"] = string("BP31.250610.004")
  }
  "android.os.Build\$VERSION" clazz {
    // Recent enough that none of Shark's known library leaks is in this dump, so that the references a
    // chain through it names are the app's own.
    staticField["SDK_INT"] = IntHolder(34)
  }
}

internal const val ACTIVITY_CLASS_NAME = "com.example.MainActivity"

internal const val HOLDER_CLASS_NAME = "com.example.Holder"

internal const val APPLICATION_CLASS_NAME = "com.example.ExampleApplication"

/** The field of the holder that keeps the activity, which is the faulty reference of this dump. */
internal const val ACTIVITY_FIELD_NAME = "activity"

/** And the field above it, which is the one a chain names while the holder has no verdict. */
internal const val HOLDER_FIELD_NAME = "holder"

/** How a chain spells the reference at fault once the holder is known to belong in memory. */
internal const val FAULTY_REFERENCE = "Holder.$ACTIVITY_FIELD_NAME"
