package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder

class AndroidMetadataExtractorTest {

  /**
   * An app that declares a `versionCodeMajor` has a version code past 32 bits, which only
   * `ApplicationInfo.longVersionCode` holds. Since API 28, `ApplicationInfo.versionCode` is its low 32 bits,
   * kept for the apps that used to read it by reflection. The one real heap dump from API 28 or later,
   * `safe_iterable_map.hprof`, is in the tests of `shark`, which can't see this module, so this one is
   * built.
   */
  @Test fun `version code is read from longVersionCode when the heap dump has it`() {
    // versionCodeMajor 1, versionCode 42.
    val longVersionCode = (1L shl 32) + 42
    val heapDump = dump {
      "android.os.Build" clazz {
        staticField["MANUFACTURER"] = string("Google")
        staticField["ID"] = string("BP31.250610.004")
      }
      "android.os.Build\$VERSION" clazz {
        staticField["SDK_INT"] = IntHolder(34)
      }
      val appInfo = "android.content.pm.ApplicationInfo" instance {
        field["longVersionCode"] = LongHolder(longVersionCode)
        field["versionCode"] = IntHolder(longVersionCode.toInt())
      }
      val appBindData = "android.app.ActivityThread\$AppBindData" instance {
        field["appInfo"] = appInfo
      }
      val activityThread = reserveObjectId()
      instance(
        classId = clazz(
          className = "android.app.ActivityThread",
          staticFields = listOf("sCurrentActivityThread" to activityThread),
          fields = listOf("mBoundApplication" to ReferenceHolder::class)
        ),
        fields = listOf(appBindData),
        objectId = activityThread
      )
    }

    val metadata = heapDump.openHeapGraph().use { graph ->
      AndroidMetadataExtractor.extractMetadata(graph)
    }

    assertThat(metadata["ApplicationInfo.versionCode"]).isEqualTo(longVersionCode.toString())
  }
}
