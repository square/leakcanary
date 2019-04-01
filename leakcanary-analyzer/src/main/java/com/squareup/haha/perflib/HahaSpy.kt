package com.squareup.haha.perflib

fun Instance.allocatingThread(): Instance {
  val snapshot = mHeap.mSnapshot
  val threadSerialNumber = if (this is RootObj) mThread else mStack.mThreadSerialNumber
  val thread = snapshot.getThread(threadSerialNumber)
  return snapshot.findInstance(thread.mId)
}