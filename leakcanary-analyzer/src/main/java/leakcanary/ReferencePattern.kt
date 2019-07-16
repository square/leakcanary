package leakcanary

import java.io.Serializable

sealed class ReferencePattern : Serializable {

  /**
   * Local references held in the stack of frames of a given thread.
   */
  data class JavaLocalPattern(
    val threadName: String
  ) : ReferencePattern() {
    override fun toString(): String {
      return "local variable on thread $threadName"
    }
  }

  data class StaticFieldPattern(
    val className: String,
    val fieldName: String
  ) : ReferencePattern() {
    override fun toString(): String {
      return "static field $className#$fieldName"
    }
  }

  /**
   * Excludes a member field of an instance of a class. [fieldName] can belong to a superclass
   * and will still match for subclasses. This is to support overriding of rules for specific
   * cases. If two exclusions for the same field name but different classname match in a class
   * hierarchy, then the closest class in the hierarchy wins.
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