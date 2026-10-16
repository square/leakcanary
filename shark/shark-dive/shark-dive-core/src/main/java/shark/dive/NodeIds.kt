package shark.dive

import shark.dive.HeapDominatorTreemap.Companion.ROOT_OBJECT_ID
import shark.dive.HeapDominatorTreemap.Companion.UNREACHABLE_NODE_ID

/**
 * The address a heap dump recorded an object at, which is what an object id is: `0x` and the id in hexadecimal,
 * unsigned. `0x12d368b8`, and `0x82000010` for an object above the 2 GB mark of a 32 bit dump, which is how
 * every other heap tool writes it too.
 *
 * Public rather than internal because everything outside this app that names an object has to spell it this
 * way and read it back with [objectIdOfHex]: the labels, the files this app keeps, the links it hands out, and
 * the agent surface, which hands addresses to a caller that will send them back. Two spellings of one address
 * across those is the bug this function exists to prevent.
 */
fun hexObjectId(objectId: Long): String = "0x${java.lang.Long.toHexString(objectId)}"

/** And back, or null for text that is no address at all. See [hexObjectId]. */
fun objectIdOfHex(text: String): Long? {
  if (!text.startsWith(HEX_PREFIX)) {
    return null
  }
  return try {
    // Unsigned, since an address that fills all 64 bits is a number no signed Long holds.
    java.lang.Long.parseUnsignedLong(text.substring(HEX_PREFIX.length), HEX_RADIX)
  } catch (notAnAddress: NumberFormatException) {
    null
  }
}

/**
 * How a node of a [HeapDominatorTreemap] reads in a message: the address of an object, or which pile of
 * objects it is.
 *
 * A pile is no object of the heap dump, so it has no address to name it by. See
 * [HeapDominatorTreemap.isPileId].
 */
fun nodeIdText(nodeId: Long): String = when {
  nodeId == ROOT_OBJECT_ID -> "the whole heap dump"
  nodeId == UNREACHABLE_NODE_ID -> "the uncollected garbage"
  HeapDominatorTreemap.isPileId(nodeId) -> "the class pile ${nodeId - UNREACHABLE_NODE_ID}"
  else -> hexObjectId(nodeId)
}

private const val HEX_PREFIX = "0x"
private const val HEX_RADIX = 16
