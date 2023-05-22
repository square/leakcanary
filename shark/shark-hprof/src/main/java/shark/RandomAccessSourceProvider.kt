package shark

/**
 * Can open [RandomAccessSource] instances.
 */
fun interface RandomAccessSourceProvider {
  fun openRandomAccessSource(): RandomAccessSource
}
