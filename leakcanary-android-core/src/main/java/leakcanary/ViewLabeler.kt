package leakcanary

import android.app.Application
import android.content.res.Resources.NotFoundException
import leakcanary.HeapValue.IntValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord

class ViewLabeler(
  private val application: Application
) : Labeler {
  override fun invoke(
    parser: HprofParser,
    node: LeakNode
  ): List<String> = with(HprofGraph(parser)) {
    val record = ObjectReference(node.instance)
        .record

    if (record instanceOf "android.view.View") {
      val viewLabels = mutableListOf<String>()
      record as InstanceDumpRecord
      val mAttachInfo = record["mAttachInfo"]
      if (mAttachInfo != null) {
        if (mAttachInfo.isNullReference) {
          viewLabels.add("View#mAttachInfo is null (view detached)")
        } else {
          viewLabels.add("View#mAttachInfo is not null (view attached)")
        }
      }

      val mParent = record["mParent"]
      if (mParent != null) {
        if (mParent.isNullReference) {
          viewLabels.add("View#mParent is null")
        } else {
          viewLabels.add("View#mParent is set")
        }
      }

      val mID = record["mId"]
      if (mID is IntValue) {
        if (mID.value != 0) {
          try {
            val name = application.resources.getResourceEntryName(mID.value)
            viewLabels.add("View.mID=R.id.$name (${mID.value})")
          } catch (ignored: NotFoundException) {
            viewLabels.add("View.mID=${mID.value} (name not found)")
          }
        } else {
          viewLabels.add("View.mID=0")
        }
      }

      val mWindowAttachCount = record["mWindowAttachCount"]

      if (mWindowAttachCount is IntValue) {
        viewLabels.add("View.mWindowAttachCount=${mWindowAttachCount.value}")
      }
      return viewLabels
    }
    return emptyList()
  }
}