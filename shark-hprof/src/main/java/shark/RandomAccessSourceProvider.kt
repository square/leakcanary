package shark

/**
 * Can open [RandomAccessSource] instances.
 */
interface RandomAccessSourceProvider {
  fun openRandomAccessSource(): RandomAccessSource
}
