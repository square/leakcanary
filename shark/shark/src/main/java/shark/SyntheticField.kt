package shark

import java.io.Serializable

/**
 * A value an [ObjectInspector] reports as if it were a field of the object. A leak trace prints it as
 * `name = value`, as in `Thread.name = "main"`.
 *
 * [name] is the simple name of the class that declares the field, a dot, and the field name:
 * `Fragment.mTag`. A value computed from several fields gets a name of the same shape, such as
 * `KeyedWeakReference.retainedDurationMillis`.
 *
 * [value] is printed as is. The built-in inspectors quote the characters of a string, print another
 * object as `android.os.Handler@0x12c0a6b8`, and print a constant by its name.
 *
 * Add one with [ObjectReporter.addSyntheticField]. A sentence goes in [ObjectReporter.labels].
 */
data class SyntheticField(
  val name: String,
  val value: String
) : Serializable {

  override fun toString(): String = "$name = $value"

  private companion object {
    private const val serialVersionUID = 1L
  }
}
