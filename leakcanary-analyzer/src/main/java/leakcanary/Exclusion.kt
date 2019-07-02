package leakcanary

import leakcanary.Exclusion.Status.WONT_FIX_LEAK
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
  val status: Status = WONT_FIX_LEAK,
  val filter: (HprofGraph) -> Boolean = {true}
) {
  val description
    get() = ExclusionDescription(type.matching, reason)

  // Note: the enum order matters for shortest paths, do not reorder
  enum class Status {
    /**
     * References matching this cannot create leaks.
     * The shortest path finder will never go through references that match this exclusion.
     */
    NEVER_REACHABLE,
    /**
     * References matching this are known to create leaks, but those leaks will not be fixed.
     * The shortest path finder will only go through references that match this exclusion after it
     * has exhausted references that don't match any exclusion.
     */
    WONT_FIX_LEAK,
    /**
     * The shortest path finder will only go through references that match this exclusion after it
     * has exhausted references that match known leak exclusions.
     */
    WEAKLY_REACHABLE
  }

  sealed class ExclusionType {
    abstract val matching: String

    /**
     * Local references held in the stack of frames of a given thread.
     */
    class JavaLocalExclusion(
      val threadName: String
    ) : ExclusionType() {
      override val matching: String
        get() = "local variable on thread $threadName"
    }

    class StaticFieldExclusion(
      val className: String,
      val fieldName: String
    ) : ExclusionType() {
      override val matching: String
        get() = "static field $className#$fieldName"
    }

    /**
     * Excludes a member field of an instance of a class. [fieldName] can belong to a superclass
     * and will still match for subclasses. This is to support overriding of rules for specific
     * cases. If two exclusions for the same field name but different classname match in a class
     * hierarchy, then the closest class in the hierarchy wins.
     */
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