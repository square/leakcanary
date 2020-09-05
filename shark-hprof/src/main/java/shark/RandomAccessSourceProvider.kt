package shark

interface RandomAccessSourceProvider {
  fun openRandomAccessSource(): RandomAccessSource
}
