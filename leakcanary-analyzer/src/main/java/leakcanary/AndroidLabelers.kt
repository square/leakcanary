package leakcanary

import leakcanary.HeapValue.ObjectReference
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord

enum class AndroidLabelers : Labeler {

  FRAGMENT_LABELER {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): List<String> = with(HprofGraph(parser)) {
      val record = ObjectReference(node.instance)
          .record
      if (record instanceOf "androidx.fragment.app.Fragment" || record instanceOf "android.app.Fragment") {
        record as InstanceDumpRecord
        val mTag = record["mTag"].record.string
        if (!mTag.isNullOrEmpty()) {
          return listOf("Fragment.mTag=$mTag")
        }
      }
      return emptyList()
    }
  };

}