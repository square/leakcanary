package leakcanary.internal

/**
 * [objectId] in hex, the way shark writes an object id for someone to read: `0x12c0a6b8`. The
 * same as the copy in `shark-graph`, which says why. This module can't see that one.
 */
internal fun hexObjectId(objectId: Long): String {
  return "0x${java.lang.Long.toHexString(objectId)}"
}
