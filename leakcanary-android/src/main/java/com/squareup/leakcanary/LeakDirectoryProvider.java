package com.squareup.leakcanary;

import java.io.File;

/** Provides the directory in which heap dumps and analysis results will be stored. */
public interface LeakDirectoryProvider {

  /** Returns a path to an existing directory were leaks can be stored. */
  File leakDirectory();

  /** True if we can currently write to the leak directory. */
  boolean isLeakStorageWritable();
}
