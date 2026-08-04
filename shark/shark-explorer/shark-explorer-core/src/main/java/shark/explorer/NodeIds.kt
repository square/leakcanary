package shark.explorer

import shark.explorer.HeapDominatorTreemap.Companion.ROOT_OBJECT_ID
import shark.explorer.HeapDominatorTreemap.Companion.UNREACHABLE_NODE_ID

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
 * How a node of either of a heap dump's two trees reads in a message: the address of an object, which pile of
 * objects it is, or which row of the tree read from the classes up.
 *
 * Neither a pile nor a row is an object of the heap dump, so neither has an address to name it by. See
 * [HeapDominatorTreemap.isPileId] and [ReverseDominatorTree.isReverseNode].
 */
fun nodeIdText(nodeId: Long): String = when {
  nodeId == ROOT_OBJECT_ID -> "the whole heap dump"
  nodeId == UNREACHABLE_NODE_ID -> "the uncollected garbage"
  ReverseDominatorTree.isReverseNode(nodeId) ->
    "the class row ${ReverseDominatorTree.FIRST_REVERSE_NODE_ID - nodeId}"
  HeapDominatorTreemap.isPileId(nodeId) -> "the class pile ${nodeId - UNREACHABLE_NODE_ID}"
  else -> hexObjectId(nodeId)
}

private const val LOW_32_BITS = 0xFFFFFFFFL
