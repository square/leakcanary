package com.squareup.leakcanary

import androidx.annotation.Keep

@Keep
object FastDump {
  init {
    try {
      System.loadLibrary("fast-dump")
    } catch (e: UnsatisfiedLinkError ) {
      e.printStackTrace()
    }
  }
  @JvmStatic
  external fun forkDumpHprof(path: String)
}