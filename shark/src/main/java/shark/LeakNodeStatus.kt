package shark

enum class LeakNodeStatus {
  /** The instance was needed and therefore expected to be reachable. */
  NOT_LEAKING,
  /** The instance was no longer needed and therefore expected to be unreachable. */
  LEAKING,
  /** No decision can be made about the provided instance. */
  UNKNOWN;
}
