package shark

/**
 * This class is kept to support backward compatible deserialization.
 */
internal enum class LeakNodeStatus {
  NOT_LEAKING,
  LEAKING,
  UNKNOWN;
}