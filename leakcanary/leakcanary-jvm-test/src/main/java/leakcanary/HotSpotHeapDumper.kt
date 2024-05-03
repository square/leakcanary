package leakcanary

import com.sun.management.HotSpotDiagnosticMXBean
import java.io.File
import java.lang.management.ManagementFactory

object HotSpotHeapDumper : HeapDumper {
  private val hotspotMBean: HotSpotDiagnosticMXBean by lazy {
    val mBeanServer = ManagementFactory.getPlatformMBeanServer()
    ManagementFactory.newPlatformMXBeanProxy(
      mBeanServer,
      "com.sun.management:type=HotSpotDiagnostic",
      HotSpotDiagnosticMXBean::class.java
    )
  }

  override fun dumpHeap(heapDumpFile: File) {
    val live = true
    hotspotMBean.dumpHeap(heapDumpFile.absolutePath, live)
  }
}

fun HeapDumper.Companion.forJvmInProcess() = HotSpotHeapDumper

