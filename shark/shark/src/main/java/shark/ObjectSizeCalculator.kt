package shark

fun interface ObjectSizeCalculator {
  fun computeSize(objectId: Long): Int
}
