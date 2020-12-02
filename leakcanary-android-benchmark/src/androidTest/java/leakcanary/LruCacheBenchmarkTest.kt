package leakcanary

import android.util.LruCache
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LruCacheBenchmarkTest {

  /*
  * Don't forget to run ./gradlew lockClocks on Rooted Device!
  */

  @get:Rule
  val lruRule = BenchmarkRule()

  @Test fun lruInsertTest() {
    val cache = LruCache<Long, String>(3000)
    lruRule.measureRepeated {
      fillCache(cache)
    }
  }

  @Test fun lruReadTest() {
    val cache = LruCache<Long, String>(3000)
    fillCache(cache)
    lruRule.measureRepeated {
      var value: String? = null
      for (i in 0..100000) {
        value = cache[i + i * 1500L]
      }
    }
  }
  private fun fillCache(cache: LruCache<Long, String>) {
    for (i in 0..100000) {
      cache.put(i + i * 1500L, VALUE)
    }
  }

  companion object {
    const val VALUE = "HAHA"
  }
}

