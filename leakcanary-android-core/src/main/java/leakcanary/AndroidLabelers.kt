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
    ): List<String> {
      val objectId = node.instance
      val record = parser.retrieveRecordById(objectId)
      if (record is InstanceDumpRecord) {
        val className = parser.className(record.classId)
        if (className == "androidx.fragment.app.Fragment" || className == "android.app.Fragment") {
          val instance = parser.hydrateInstance(record)
          val mTagId = instance.fieldValueOrNull<HeapValue>("mTag")
          if (mTagId is ObjectReference && !mTagId.isNull) {
            val mTag = parser.retrieveString(mTagId)
            if (mTag.isNotEmpty()) {
              return listOf("Fragment.mTag=$mTag")
            }
          }
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
    ): List<String> {
      val objectId = node.instance
      val record = parser.retrieveRecordById(objectId)
      if (record is InstanceDumpRecord) {
        val instance = parser.hydrateInstance(record)
        if (instance.isInstanceOf(View::class.java.name)) {
          val viewLabels = mutableListOf<String>()

          if (instance.hasField("mAttachInfo")) {
            if (instance["mAttachInfo"].reference == null) {
              viewLabels.add("View#mAttachInfo is null (view detached)")
            } else {
              viewLabels.add("View#mAttachInfo is not null (view attached)")
            }
          }
          if (instance["mParent"].reference == null) {
            viewLabels.add("View#mParent is null")
          } else {
            viewLabels.add("View#mParent is set")
          }

          val mID = instance.fieldValueOrNull<HeapValue>("mID")
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
          val mWindowAttachCount = instance.fieldValueOrNull<HeapValue>("mWindowAttachCount")
          if (mWindowAttachCount is IntValue) {
            viewLabels.add("View.mWindowAttachCount=${mWindowAttachCount.value}")
          }
          return viewLabels
        }
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