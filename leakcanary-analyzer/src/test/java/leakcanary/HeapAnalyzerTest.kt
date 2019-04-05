package leakcanary

import com.squareup.haha.perflib.RootObj
import com.squareup.haha.perflib.RootType.NATIVE_STATIC
import com.squareup.haha.perflib.RootType.SYSTEM_CLASS
import com.squareup.haha.perflib.Snapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Arrays.asList

@RunWith(JUnit4::class)
class HeapAnalyzerTest {

  private lateinit var heapAnalyzer: HeapAnalyzer

  @Before
  fun setUp() {
    heapAnalyzer = HeapAnalyzer(
        NO_EXCLUDED_REFS, AnalyzerProgressListener.NONE,
        emptyList()
    )
  }

  @Test
  fun ensureUniqueRoots() {
    val snapshot = createSnapshot(DUP_ROOTS)

    heapAnalyzer.deduplicateGcRoots(snapshot)

    val uniqueRoots = snapshot.gcRoots
    assertThat(uniqueRoots).hasSize(4)

    val rootIds = mutableListOf<Long>()
    uniqueRoots.forEach { root ->
      rootIds.add(root.id)
    }
    rootIds.sort()

    // 3 appears twice because even though two RootObjs have the same id, they're different types.
    assertThat(rootIds).containsExactly(3L, 3L, 5L, 6L)
  }

  private fun createSnapshot(gcRoots: List<RootObj>): Snapshot {
    val snapshot = Snapshot(null)
    for (root in gcRoots) {
      snapshot.addRoot(root)
    }
    return snapshot
  }

  companion object {
    private val DUP_ROOTS = asList<RootObj>(
        RootObj(SYSTEM_CLASS, 6L),
        RootObj(SYSTEM_CLASS, 5L),
        RootObj(SYSTEM_CLASS, 3L),
        RootObj(SYSTEM_CLASS, 5L),
        RootObj(NATIVE_STATIC, 3L)
    )
  }
}
