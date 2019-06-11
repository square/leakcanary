package leakcanary

import android.app.Application
import android.content.res.Resources.NotFoundException
import android.view.View
import leakcanary.HeapValue.IntValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import java.util.ArrayList

enum class AndroidLabelers : Labeler {

  FRAGMENT_LABELER {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): List<String> = with(HprofGraph(parser)) {
      val record = ObjectReference(node.instance).record
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

  class ViewLabeler(
    private val application: Application
  ) : Labeler {
    override fun invoke(
      parser: HprofParser,
      node: LeakNode
    ): List<String> = with(HprofGraph(parser)) {
      val record = ObjectReference(node.instance).record

      if (record instanceOf View::class) {
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

  companion object {
    fun defaultAndroidLabelers(application: Application): List<Labeler> {
      val labelers = ArrayList<Labeler>()
      labelers.add(InstanceDefaultLabeler)
      labelers.add(
          ViewLabeler(
              application
          )
      )
      labelers.addAll(values())
      return labelers
    }
  }

}