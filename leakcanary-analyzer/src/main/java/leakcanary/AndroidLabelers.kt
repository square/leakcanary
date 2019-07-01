package leakcanary

import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.HeapValue.ObjectReference

enum class AndroidLabelers : Labeler {

  FRAGMENT_LABELER {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): List<String> {
      val heapValue = GraphHeapValue(HprofGraph(parser), ObjectReference(node.instance))
      val record = heapValue.readObjectRecord()!!

      if (record is GraphInstanceRecord && (
              record instanceOf "androidx.fragment.app.Fragment" ||
                  record instanceOf "android.app.Fragment")
      ) {
        val mTag = record["mTag"]?.value?.readAsJavaString()
        if (!mTag.isNullOrEmpty()) {
          return listOf("Fragment.mTag=$mTag")
        }
      }

      return emptyList()
    }
  }
  ;

}