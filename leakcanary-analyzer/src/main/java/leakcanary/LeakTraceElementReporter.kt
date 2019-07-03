package leakcanary

class LeakTraceElementReporter(val objectRecord: GraphObjectRecord) {

  private val mutableLabels = mutableListOf<String>()
  private val mutableLeakNodeStatuses = mutableListOf<LeakNodeStatusAndReason>()

  val labels: List<String>
    get() = mutableLabels

  val leakNodeStatuses: List<LeakNodeStatusAndReason>
    get() = mutableLeakNodeStatuses

  fun addLabel(label: String) {
    mutableLabels += label
  }

  fun reportLeaking(reason: String) {
    mutableLeakNodeStatuses += LeakNodeStatus.leaking(reason)
  }

  fun reportNotLeaking(reason: String) {
    mutableLeakNodeStatuses += LeakNodeStatus.notLeaking(reason)
  }

}