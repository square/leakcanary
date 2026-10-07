package shark.internal

/**
 * [objectId] the way shark writes an object id for someone to read: `0x12c0a6b8`.
 *
 * In hex because that is how every tool that reads a heap dump writes an address, so an id copied out
 * of a leak trace or an error can be searched for in any of them. Shark Dive turns an id written this
 * way into a link to the object.
 *
 * A 32 bit heap dump records an id in 4 bytes and shark widens it by sign, so an object above the 2 GB
 * mark has a negative id. Its low 32 bits are the address, and what every other tool prints.
 *
 * `shark`, `shark-android`, `shark-cli` and `leakcanary-android-core` each have a copy, since none of
 * them can see this one.
 */
internal fun hexObjectId(objectId: Long): String {
  val address = if (objectId < 0L) objectId and LOW_32_BITS else objectId
  return "0x${address.toString(16)}"
}

private const val LOW_32_BITS = 0xffffffffL
