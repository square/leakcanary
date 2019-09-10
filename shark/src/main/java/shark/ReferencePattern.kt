package shark

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
    override fun toString() = "local variable on thread $threadName"
  }

  /**
   * Matches static field references, identified by [className] and [fieldName].
   */
  data class StaticFieldPattern(
    val className: String,
    val fieldName: String
  ) : ReferencePattern() {
    override fun toString() = "static field $className#$fieldName"
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
    override fun toString() = "instance field $className#$fieldName"
  }

  /**
   * Matches native global variables (also known as jni global gc roots) that reference
   * Java objects. The class name will match against classes, instances and object arrays with
   * a matching class name.
   */
  data class NativeGlobalVariablePattern(val className: String) : ReferencePattern() {
    override fun toString() = "native global variable referencing $className"
  }
}