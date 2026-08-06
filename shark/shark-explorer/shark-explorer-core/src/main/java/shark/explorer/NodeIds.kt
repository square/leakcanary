package shark.explorer

import shark.explorer.SemanticDominatorTreemap.Companion.ROOT_OBJECT_ID
import shark.explorer.SemanticDominatorTreemap.Companion.UNREACHABLE_NODE_ID

/**
 * The address a heap dump recorded an object at, which is what an object id is.
 *
 * A 32 bit heap dump records an id in 4 bytes and shark widens it by sign, so an object above the 2 GB mark
 * has a negative id: an address rather than a number to write behind a minus sign, and the low 32 bits are
 * it. Which is why this exists at all — `"0x" + objectId.toString(16)` reads as `0x-7deb3000` for the
 * bitmaps and byte arrays of a large Android dump, and no other tool will recognise that.
 */
fun hexObjectId(objectId: Long): String {
  val address = if (objectId < 0L) objectId and LOW_32_BITS else objectId
  return "0x${address.toString(16)}"
}

/**
 * How a node of a [SemanticDominatorTreemap] reads in a message: the address of an object, or which pile of
 * objects it is.
 *
 * A pile is no object of the heap dump, so it has no address to name it by. See
 * [SemanticDominatorTreemap.isPileId].
 */
fun nodeIdText(nodeId: Long): String = when {
  nodeId == ROOT_OBJECT_ID -> "the whole heap dump"
  nodeId == UNREACHABLE_NODE_ID -> "the uncollected garbage"
  SemanticDominatorTreemap.isPileId(nodeId) -> "the class pile ${nodeId - UNREACHABLE_NODE_ID}"
  else -> hexObjectId(nodeId)
}

private const val LOW_32_BITS = 0xFFFFFFFFL
