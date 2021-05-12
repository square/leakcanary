package com.squareup.leakcanary

import androidx.annotation.Keep
import shark.SharkLog

@Keep
object FastDump {
  init {
    try {
      System.loadLibrary("fast-dump")
    } catch (e: UnsatisfiedLinkError) {
      SharkLog.d { "Fast dump LoadLibrary failed: $e" }
    }
  }

  @JvmStatic
  external fun forkDumpHprof(path: String)
}