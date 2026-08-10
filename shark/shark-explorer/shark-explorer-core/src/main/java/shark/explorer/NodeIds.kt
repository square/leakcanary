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
 * The same address, written so that it reads back as the same [Long] — for the files this app keeps between
 * runs. See [LeakStatusFile].
 *
 * The one thing [hexObjectId] gives up to be recognisable is exactly what a file can't: it prints a
 * sign-widened id as the 32 bit address it is, and `0xffff8000` is then two ids, the negative one of a 32 bit
 * dump and the positive one a 64 bit dump could have. So a file that meant to carry one object's address and
 * is read back against another dump — or the same dump on a machine that reads it the other way — would name
 * an object nobody chose. This is all 16 digits of it for such an id, and identical to [hexObjectId] for every
 * other, which is every object of every 64 bit dump below the 8 exabyte mark.
 */
internal fun exactHexObjectId(objectId: Long): String = "0x${java.lang.Long.toHexString(objectId)}"

/** And back, or null for text that is no address at all. See [exactHexObjectId]. */
internal fun objectIdOfHex(text: String): Long? {
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

private const val LOW_32_BITS = 0xFFFFFFFFL

private const val HEX_PREFIX = "0x"
private const val HEX_RADIX = 16
