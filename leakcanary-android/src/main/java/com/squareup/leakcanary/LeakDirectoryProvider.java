/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import java.io.File;
import java.io.FilenameFilter;
import java.util.List;

/**
 * Provides access to where heap dumps and analysis results will be stored.
 * When using your own implementation, you should also call {@link
 * LeakCanary#setDisplayLeakActivityDirectoryProvider(LeakDirectoryProvider)} to ensure the
 * provided activity is able to display the leaks.
 */
public interface LeakDirectoryProvider {

  @NonNull List<File> listFiles(@NonNull FilenameFilter filter);

  /**
   * @return {@link HeapDumper#RETRY_LATER} if a new heap dump file could not be created.
   */
  @Nullable File newHeapDumpFile();

  /**
   * Removes all heap dumps and analysis results, except for heap dumps that haven't been
   * analyzed yet.
   */
  void clearLeakDirectory();
}
