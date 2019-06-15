package leakcanary

import android.app.Application
import leakcanary.Exclusion.ExclusionType.InstanceFieldExclusion
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import org.junit.Test
import java.io.File

class FooTest {

  @Test fun from_zac() {
    val filename = "2019-06-10_18-25-43_753.hprof"
    analyze(filename)
  }

  @Test fun foobar() {
    // "2019-05-07_11-30-59_244.hprof" is POS only
    // "2019-05-23_15-51-23_724.hprof" is POS only
    // 2019-05-23_15-51-49_551.hprof is POS only
    // 2019-06-03_15-03-16_892.hprof is POS only
    // 2019-06-03_15-09-28_448.hprof pos only
    // 2019-06-04_11-32-50_076.hprof => not POS specific. Held by ViewRootImpl$W.mViewAncestor, weird
    // 2019-06-04_11-57-49_719.hprof => IMM leak, same as zac
    // 2019-06-04_11-58-22_358.hprof => IMM leak, same as zac
    // 2019-06-11_12-28-54_059.hprof => accessibility node manager leak
    // 2019-06-12_12-29-20_004.hprof repro accessibility leak



    val filename = "2019-06-11_12-28-54_059.hprof"
    analyze(filename)
  }

  private fun analyze(filename: String) {
    val classLoader = Thread.currentThread()
        .contextClassLoader
    val url = classLoader.getResource(filename)

    val hprofFile = File(url.getPath())

    val heapAnalyzer = HeapAnalyzer(AnalyzerProgressListener.NONE)

    val exclusionsFactory: ExclusionsFactory = {
      val default = AndroidExcludedRefs.exclusionsFactory(
          AndroidExcludedRefs.appDefaults
      )
      default(it) // + Exclusion(InstanceFieldExclusion("android.view.InsetsController" ,"mViewRoot"))
    }

    val leakInspectors = AndroidLeakInspectors.defaultAndroidInspectors() + { parser, node ->
      with(parser) {

        val record = node.instance.objectRecord

        if (record is InstanceDumpRecord && parser.className(
                record.classId
            ) == "android.view.ViewRootImpl"
        ) {
          val instance = parser.hydrateInstance(record)
          val contextValue = instance["mContext"]
          val ref = contextValue.reference!!
          val context = ref.objectRecord.hydratedInstance

          val weakRef =
            context["mActivityContext"].reference!!.objectRecord.hydratedInstance

          val reference = weakRef["referent"].reference
          if (reference == null) {
            LeakNodeStatus.unknown()
          } else {
            val activity = reference.objectRecord.hydratedInstance
            val mDestroyed = activity["mDestroyed"].boolean!!
            if (mDestroyed) LeakNodeStatus.leaking(
                "Activity destroyed"
            ) else LeakNodeStatus.notLeaking("Activity not destroyed")
          }
        } else {
          LeakNodeStatus.unknown()
        }
      }
    } + { parser, node ->
      with(parser) {

        val record = node.instance.objectRecord

        if (record is InstanceDumpRecord && parser.className(
                record.classId
            ) == "android.view.inputmethod.InputMethodManager"
        ) {
          LeakNodeStatus.notLeaking("InputMethodManager is a singleton")
        } else {
          LeakNodeStatus.unknown()
        }
      }
    }
    val labelers = AndroidLabelers.defaultAndroidLabelers(
        Application()
    ) + { parser, node ->
      with(parser) {
        val labels = mutableListOf<String>()

        val record = node.instance.objectRecord



        if (record is InstanceDumpRecord && parser.className(
                record.classId
            ) == "android.view.ViewRootImpl"
        ) {

          val instance = parser.hydrateInstance(record)
          val contextValue = instance["mContext"]
          val ref = contextValue.reference!!
          val context = ref.objectRecord.hydratedInstance

          val weakRef =
            context["mActivityContext"].reference!!.objectRecord.hydratedInstance

          val reference = weakRef["referent"].reference
          if (reference == null) {
            labels += "weak ref has null activity"

          } else {
            val activity = reference.objectRecord.hydratedInstance
            labels += "Activity ${activity.record.id} class ${activity.classHierarchy[0].className} mDestroyed: ${activity["mDestroyed"]}"
          }

        }

        if (record is InstanceDumpRecord && parser.className(
                record.classId
            ) == "flow.path.PathContext"
        ) {

          var currentRecord = record
          while ((currentRecord is InstanceDumpRecord && parser.className(
                  currentRecord.classId
              ) in listOf("flow.path.PathContext", "flow.path.Path\$LocalPathWrapper", "mortar.MortarContextWrapper"))
          ) {
            val instance = currentRecord.hydratedInstance
            currentRecord = instance["mBase"].reference!!.objectRecord
          }

          val context = currentRecord.hydratedInstance

          labels += "wraps ${context.record.id} class ${context.classHierarchy[0].className} mDestroyed: ${context["mDestroyed"]}"
        }
        labels
      }
    }

    val analysis =
      heapAnalyzer.checkForLeaks(hprofFile, exclusionsFactory, false, leakInspectors, labelers)

    println(analysis)
  }

}