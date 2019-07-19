package leakcanary

import java.io.Serializable

/**
 * A pattern that will match references for a given [ReferenceMatcher].
 */
sealed class ReferencePattern : Serializable {

  /**
   * Matches local references held in the stack of frames of a given thread, identified by its name.
   */
  data class JavaLocalPattern(
    val threadName: String
  ) : ReferencePattern() {
    override fun toString(): String {
      return "local variable on thread $threadName"
    }
  }

  /**
   * Matches static field references, identified by [className] and [fieldName].
   */
  data class StaticFieldPattern(
    val className: String,
    val fieldName: String
  ) : ReferencePattern() {
    override fun toString(): String {
      return "static field $className#$fieldName"
    }
  }

  /**
   * Matches instances field references, identified by [className] and [fieldName].
   *
   * Note: If [fieldName] is declared in a superclass it will still match for subclasses.
   * This is to support overriding of rules for specific cases. If two [ReferenceMatcher] match for
   * the same [fieldName] but for different [className] in a class hierarchy, then the closest
   * class in the hierarchy wins.
   */
  data class InstanceFieldPattern(
    val className: String,
    val fieldName: String
  ) : ReferencePattern() {
    override fun toString(): String {
      return "instance field $className#$fieldName"
    }
  }
}