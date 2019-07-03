package leakcanary

import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.HeapValue.ObjectReference

enum class AndroidLabelers : Labeler {

  FRAGMENT_LABELER {
    override fun invoke(record: GraphObjectRecord): List<String> {
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