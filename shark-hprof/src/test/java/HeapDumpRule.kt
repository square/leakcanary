import com.sun.management.HotSpotDiagnosticMXBean
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder

import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory
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
        val hotSpotDiag = ManagementFactory.newPlatformMXBeanProxy(
                ManagementFactory.getPlatformMBeanServer(),
                "com.sun.management:type=HotSpotDiagnostic",
                HotSpotDiagnosticMXBean::class.java
        )
        val hprof = File(temporaryFolder.root, "heapDump" + UUID.randomUUID() + ".hprof")
        hotSpotDiag.dumpHeap(hprof.absolutePath, true)
        return hprof
    }
}
