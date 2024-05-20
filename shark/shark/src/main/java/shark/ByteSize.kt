package shark

/**
 * Inspired by https://github.com/saket/file-size as well as Kotlin's Duration API.
 */
// DO NOT ADD A COMPANION OBJECT: a value class is supposed to be lightweight and its usage inlined
// into few instructions. After adding a companion object, call sites get a lot more instructions.
@JvmInline
value class ByteSize internal constructor(
  val inWholeBytes: Long
) : Comparable<ByteSize> {

  val inWholeKilobytes: Long
    get() = inWholeBytes / BYTES_PER_KB

  val inWholeMegabytes: Long
    get() = inWholeBytes / BYTES_PER_MB

  val inWholeGigabytes: Long
    get() = inWholeBytes / BYTES_PER_GB

  override fun toString(): String {
    return when {
      inWholeBytes < BYTES_PER_KB -> "$inWholeBytes B"
      inWholeBytes < BYTES_PER_MB -> "$inWholeKilobytes KB"
      inWholeBytes < BYTES_PER_GB -> "$inWholeMegabytes MB"
      else -> "$inWholeGigabytes GB"
    }
  }

  override operator fun compareTo(other: ByteSize) = inWholeBytes.compareTo(other.inWholeBytes)

  operator fun plus(other: ByteSize): ByteSize =
    ByteSize(inWholeBytes = inWholeBytes + other.inWholeBytes)

  operator fun minus(other: ByteSize): ByteSize =
    ByteSize(inWholeBytes = inWholeBytes - other.inWholeBytes)

  operator fun times(other: ByteSize): ByteSize =
    ByteSize(inWholeBytes * other.inWholeBytes)

  operator fun div(other: ByteSize): ByteSize =
    ByteSize(inWholeBytes / other.inWholeBytes)
}

const val BYTES_PER_KB: Long = 1_000L
const val BYTES_PER_MB: Long = 1_000L * BYTES_PER_KB
const val BYTES_PER_GB: Long = 1_000L * BYTES_PER_MB

val ZERO_BYTES: ByteSize = ByteSize(0L)

val Long.bytes get() = ByteSize(this)
val Long.kilobytes get() = ByteSize(this * BYTES_PER_KB)
val Long.megabytes get() = ByteSize(this * BYTES_PER_MB)
val Long.gigabytes get() = ByteSize(this * BYTES_PER_GB)

val Int.bytes get() = ByteSize(toLong())
val Int.kilobytes get() = ByteSize(this * BYTES_PER_KB)
val Int.megabytes get() = ByteSize(this * BYTES_PER_MB)
val Int.gigabytes get() = ByteSize(this * BYTES_PER_GB)
