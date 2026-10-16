package shark.dive

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.dive.HeapDominatorTreemap.Companion.ROOT_OBJECT_ID
import shark.dive.HeapDominatorTreemap.Companion.UNREACHABLE_NODE_ID

class NodeIdsTest {

  @Test fun `an object id reads as the address it is`() {
    assertThat(hexObjectId(0x2b09c880L)).isEqualTo("0x2b09c880")
  }

  @Test fun `an object above the 2 GB mark reads as its address and back`() {
    // Where a large Android dump keeps the pixels of its bitmaps, with the top bit of a 4 byte id set.
    assertThat(hexObjectId(0x8214d000L)).isEqualTo("0x8214d000")
    assertThat(objectIdOfHex("0x8214d000")).isEqualTo(0x8214d000L)
  }

  @Test fun `a pile of objects reads as which pile it is`() {
    assertThat(nodeIdText(ROOT_OBJECT_ID)).isEqualTo("the whole heap dump")
    assertThat(nodeIdText(UNREACHABLE_NODE_ID)).isEqualTo("the uncollected garbage")
    // A class pile is one of however many a tree hands out, so there is no name to give it but its number.
    assertThat(nodeIdText(UNREACHABLE_NODE_ID + 1)).isEqualTo("the class pile 1")
  }
}
