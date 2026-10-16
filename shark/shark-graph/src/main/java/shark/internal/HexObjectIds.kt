package shark.internal

/**
 * [objectId] the way shark writes an object id for someone to read: `0x12c0a6b8`.
 *
 * In hex because that is how every tool that reads a heap dump writes an address, so an id copied out
 * of a leak trace or an error can be searched for in any of them. Shark Dive turns an id written this
 * way into a link to the object.
 *
 * Unsigned, so that an id that is no address of the heap dump, like a negative one somebody passed in,
 * is still written as the [Long] it is and reads back as that.
 *
 * `shark`, `shark-android`, `shark-cli` and `leakcanary-android-core` each have a copy, since none of
 * them can see this one.
 */
internal fun hexObjectId(objectId: Long): String = "0x${java.lang.Long.toHexString(objectId)}"
