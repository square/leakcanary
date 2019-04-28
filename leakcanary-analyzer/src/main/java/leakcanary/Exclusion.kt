package leakcanary

import java.io.Serializable

/**
 * Deprioritizes specific references from being taken into account when computing the shortest strong
 * reference path from a suspected leaking instance to the GC roots.
 *
 * This class lets you ignore known memory leaks that you known about. If the shortest path
 * matches [Exclusion], than the heap analyzer should look for a longer path with nothing
 * matching in [Exclusion].
 *
 * Exclusions should be used to match patterns of known leaks that are beyond your control, for
 * examples leaks in the Android Framework or in 3rd party libraries. This won't fix the leaks,
 * and LeakCanary will still trigger, but at least it'll indicate that there's nothing you can do
 * about it.
 */
data class Exclusion(
  val type: ExclusionType,
  val reason: String? = null,
  val alwaysExclude: Boolean = false
) {
  val description
    get() = ExclusionDescription(type.matching, reason)

  sealed class ExclusionType {
    abstract val matching: String

    class ClassExclusion(
      val className: String
    ) : ExclusionType() {
      override val matching: String
        get() = "any subclass of $className"
    }

    class ThreadExclusion(
      val threadName: String
    ) : ExclusionType() {
      override val matching: String
        get() = "any threads named $threadName"
    }

    class StaticFieldExclusion(
      val className: String,
      val fieldName: String
    ) : ExclusionType() {
      override val matching: String
        get() = "static field $className#$fieldName"
    }

    class InstanceFieldExclusion(
      val className: String,
      val fieldName: String
    ) : ExclusionType() {
      override val matching: String
        get() = "field $className#$fieldName"
    }
  }
}

class ExclusionDescription(
  val matching: String,
  val reason: String? = null
) : Serializable