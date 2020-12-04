package shark

import com.sun.management.HotSpotDiagnosticMXBean
import java.lang.management.ManagementFactory

object JvmTestHeapDumper {
  private val hotspotMBean: HotSpotDiagnosticMXBean by lazy {
    val mBeanServer = ManagementFactory.getPlatformMBeanServer()
    ManagementFactory.newPlatformMXBeanProxy(
      mBeanServer,
      "com.sun.management:type=HotSpotDiagnostic",
      HotSpotDiagnosticMXBean::class.java
    )
  }

  fun dumpHeap(
    fileName: String
  ) {
    val live = true
    hotspotMBean.dumpHeap(fileName, live)
  }
}