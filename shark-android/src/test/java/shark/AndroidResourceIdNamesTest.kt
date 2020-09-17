package shark

import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.AndroidResourceIdNames.Companion.FIRST_APP_RESOURCE_ID
import shark.AndroidResourceIdNames.Companion.RESOURCE_ID_TYPE_ITERATOR
import java.io.File

class AndroidResourceIdNamesTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Before fun setUp() {
    AndroidResourceIdNames.resetForTests()
  }

  @After fun tearDown() {
    AndroidResourceIdNames.resetForTests()
  }

  @Test fun `saveToMemory call is cached`() {
    val getResourceTypeName: (Int) -> String? = mock()

    for (i in 0..2) {
      AndroidResourceIdNames.saveToMemory(
          getResourceTypeName = getResourceTypeName,
          getResourceEntryName = { null }
      )
    }

    verify(getResourceTypeName).invoke(any())
  }

  @Test fun `AndroidResourceIdNames available in heap dump when saveToMemory is called`() {
    AndroidResourceIdNames.saveToMemory(
        getResourceTypeName = { null }, getResourceEntryName = { null })

    dumpAndReadHeap { resIdNames ->
      assertThat(resIdNames).isNotNull
    }
  }

  @Test fun `AndroidResourceIdNames not available in heap dump when saveToMemory is not called`() {
    dumpAndReadHeap { resIdNames ->
      assertThat(resIdNames).isNull()
    }
  }


  @Test fun `saveToMemory stores and retrieves resource id`() {
    val firstIdResourceId = FIRST_APP_RESOURCE_ID

    val getResourceTypeName =
      createGetResourceTypeName(mapOf(firstIdResourceId to "id"))

    val getResourceEntryName =
      createGetResourceEntryName(mapOf(FIRST_APP_RESOURCE_ID to "view_container"))

    AndroidResourceIdNames.saveToMemory(getResourceTypeName, getResourceEntryName)

    dumpAndReadHeap { resIdNames ->
      assertThat(resIdNames!![firstIdResourceId]).isEqualTo("view_container")
    }
  }

  @Test fun `id type starts after layout`() {
    val layoutResourceId = FIRST_APP_RESOURCE_ID
    val firstIdResourceId = layoutResourceId + RESOURCE_ID_TYPE_ITERATOR

    val getResourceTypeName =
      createGetResourceTypeName(
          mapOf(
              layoutResourceId to "layout",
              firstIdResourceId to "id"
          )
      )
    val getResourceEntryName =
      createGetResourceEntryName(mapOf(firstIdResourceId to "view_container"))

    AndroidResourceIdNames.saveToMemory(getResourceTypeName, getResourceEntryName)

    dumpAndReadHeap { resIdNames ->
      assertThat(resIdNames!![firstIdResourceId]).isEqualTo("view_container")
    }
  }

  @Test fun `two consecutive id resource ids`() {
    val firstIdResourceId = FIRST_APP_RESOURCE_ID
    val secondIdResourceId = FIRST_APP_RESOURCE_ID + 1

    val getResourceTypeName =
      createGetResourceTypeName(
          mapOf(
              firstIdResourceId to "id"
          )
      )
    val getResourceEntryName = createGetResourceEntryName(
        mapOf(
            firstIdResourceId to "view_container",
            secondIdResourceId to "menu_button"
        )
    )

    AndroidResourceIdNames.saveToMemory(getResourceTypeName, getResourceEntryName)

    dumpAndReadHeap { resIdNames ->
      assertThat(resIdNames!![secondIdResourceId]).isEqualTo("menu_button")
    }
  }

  private fun createGetResourceEntryName(resourceIdMap: Map<Int, String>): (Int) -> String? {
    val getResourceEntryName: (Int) -> String? = mock()
    resourceIdMap.forEach { (resourceId, resourceName) ->
      whenever(getResourceEntryName.invoke(resourceId)).thenReturn(resourceName)
    }
    return getResourceEntryName
  }

  private fun createGetResourceTypeName(resourceIdMap: Map<Int, String>): (Int) -> String? {
    val getResourceTypeName: (Int) -> String? = mock()
    resourceIdMap.forEach { (resourceId, resourceName) ->
      whenever(getResourceTypeName.invoke(resourceId)).thenReturn(resourceName)
    }
    return getResourceTypeName
  }

  fun dumpAndReadHeap(block: (AndroidResourceIdNames?) -> Unit) {
    val hprofFolder = testFolder.newFolder()
    val hprofFile = File(hprofFolder, "heapdump.hprof")
    JvmTestHeapDumper.dumpHeap(hprofFile.absolutePath)
    Hprof.open(hprofFile)
        .use { hprof ->
          val graph = HprofHeapGraph.indexHprof(hprof)
          val idNames = AndroidResourceIdNames.readFromHeap(graph)
          block(idNames)
        }
  }

}