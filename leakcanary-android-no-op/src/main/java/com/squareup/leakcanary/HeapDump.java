package com.squareup.leakcanary;

import java.io.Serializable;

/**
 * Dummy class for no-op version.
 */
public class HeapDump implements Serializable {
  public interface Listener {
    void analyze(HeapDump heapDump);
  }
}
