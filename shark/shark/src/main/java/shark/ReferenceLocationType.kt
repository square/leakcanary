package shark

/**
 * TODO This is quite similar to the leaktrace equivalent
 */
enum class ReferenceLocationType {
  INSTANCE_FIELD,
  STATIC_FIELD,
  /**
   * A local variable of a Java method running on a thread: a `JAVA_FRAME` root, read as a reference
   * from that thread by [JavaLocalReferenceReader].
   */
  LOCAL,
  ARRAY_ENTRY,
  /**
   * An object that native code running on a thread holds, typically for the length of a call: a
   * `JNI_LOCAL` or `NATIVE_STACK` root, read as a reference from that thread by
   * [JavaLocalReferenceReader]. The native counterpart of [LOCAL].
   */
  NATIVE_LOCAL
}
