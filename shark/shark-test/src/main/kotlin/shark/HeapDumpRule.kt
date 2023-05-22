package shark

import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

class HeapDumpRule : ExternalResource() {
  private val temporaryFolder = TemporaryFolder()

  @Throws(Throwable::class)
  override fun before() {
    temporaryFolder.create()
  }

  override fun after() {
    temporaryFolder.delete()
  }

  @Throws(IOException::class)
  fun dumpHeap(): File {
    val hprof = File(temporaryFolder.root, "heapDump" + UUID.randomUUID() + ".hprof")
    JvmTestHeapDumper.dumpHeap(hprof.absolutePath)
    return hprof
  }
}
