package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.HeapDominatorTreemap.Companion.GC_ROOTS_NODE_ID

class NodeIdsTest {

  @Test fun `an object id reads as the address it is`() {
    assertThat(hexObjectId(0x2b09c880L)).isEqualTo("0x2b09c880")
  }

  @Test fun `an object above the 2 GB mark reads as its address rather than as a negative number`() {
    // What shark's reader makes of the 4 byte id 0x8214d000, which is where a large Android dump keeps the
    // pixels of its bitmaps. Printing the long as it stands gives 0x-7deb3000, an address no other tool
    // and no colleague will recognise.
    assertThat(hexObjectId(-2112565248L)).isEqualTo("0x8214d000")
  }

  @Test fun `a pile of objects reads as which pile it is`() {
    assertThat(nodeIdText(GC_ROOTS_NODE_ID)).isEqualTo("everything the GC roots reach")
    assertThat(nodeIdText(HeapDominatorTreemap.ROOT_OBJECT_ID)).isEqualTo("the whole heap dump")
  }
}
