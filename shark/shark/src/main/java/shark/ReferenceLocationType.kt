package shark

/**
 * TODO This is quite similar to the leaktrace equivalent
 */
enum class ReferenceLocationType {
  INSTANCE_FIELD,
  STATIC_FIELD,
  LOCAL,
  ARRAY_ENTRY
}
