package leakcanary

import android.app.Application
import android.content.res.Resources.NotFoundException
import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.HeapValue.ObjectReference

class ViewLabeler(
  private val application: Application
) : Labeler {
  override fun invoke(
    parser: HprofParser,
    node: LeakNode
  ): List<String> {
    val heapValue = GraphHeapValue(HprofGraph(parser), ObjectReference(node.instance))
    val record = heapValue.readObjectRecord()

    if (record is GraphInstanceRecord && record instanceOf "android.view.View") {
      val viewLabels = mutableListOf<String>()
      val mAttachInfo = record["mAttachInfo"]?.value
      if (mAttachInfo != null) {
        if (mAttachInfo.isNullReference) {
          viewLabels.add("View#mAttachInfo is null (view detached)")
        } else {
          viewLabels.add("View#mAttachInfo is not null (view attached)")
        }
      }

      val mParent = record["mParent"]?.value
      if (mParent != null) {
        if (mParent.isNullReference) {
          viewLabels.add("View#mParent is null")
        } else {
          viewLabels.add("View#mParent is set")
        }
      }

      val mID = record["mId"]?.value?.asInt
      if (mID != null) {
        if (mID != 0) {
          try {
            val name = application.resources.getResourceEntryName(mID)
            viewLabels.add("View.mID=R.id.$name ($mID)")
          } catch (ignored: NotFoundException) {
            viewLabels.add("View.mID=$mID (name not found)")
          }
        } else {
          viewLabels.add("View.mID=0")
        }
      }

      val mWindowAttachCount = record["mWindowAttachCount"]?.value?.asInt

      if (mWindowAttachCount != null) {
        viewLabels.add("View.mWindowAttachCount=$mWindowAttachCount")
      }
      return viewLabels
    }
    return emptyList()
  }
}