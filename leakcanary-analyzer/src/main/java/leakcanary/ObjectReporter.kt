package leakcanary

class ObjectReporter(val objectRecord: GraphObjectRecord) {

  private val mutableLabels = mutableListOf<String>()

  private val mutableLeakingStatuses = mutableListOf<LeakNodeStatusAndReason>()
  private val mutableLikelyLeakingStatuses = mutableListOf<LeakNodeStatusAndReason>()
  private val mutableNotLeakingStatuses = mutableListOf<LeakNodeStatusAndReason>()

  val labels: List<String>
    get() = mutableLabels

  val leakNodeStatuses: List<LeakNodeStatusAndReason>
    get() = mutableLeakingStatuses + mutableLikelyLeakingStatuses + mutableNotLeakingStatuses

  val leakingStatuses: List<LeakNodeStatusAndReason>
    get() = mutableLeakingStatuses

  fun addLabel(label: String) {
    mutableLabels += label
  }


  /**
   * The inspector is almost sure this instance is leaking, but not 100%. This information will
   * be used for decorating leaktraces, but [HeapAnalyzer] will not look for these instances.
   */
  fun reportLikelyLeaking(reason: String) {
    mutableLikelyLeakingStatuses += LeakNodeStatus.leaking(reason)
  }

  /**
   * The inspector is 100% sure this instance is leaking. [HeapAnalyzer] will look for these
   * instances.
   */
  fun reportLeaking(reason: String) {
    mutableLeakingStatuses += LeakNodeStatus.leaking(reason)
  }

  fun reportNotLeaking(reason: String) {
    mutableNotLeakingStatuses += LeakNodeStatus.notLeaking(reason)
  }

}