/*
 * Copyright (C) 2015 Square, Inc.
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

import java.io.File;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertArrayEquals;

@RunWith(Parameterized.class) //
public class AutopsyTest {

  @Parameterized.Parameters public static Collection<Object[]> data() {
    return asList(new Object[][] {
        {
            fileFromName("leak_asynctask.hprof"), //
            asList( //
                "com.example.leakcanary.MainActivity", //
                "com.android.internal.view.menu.ActionMenuPresenter$OverflowMenuButton", //
                "com.android.internal.widget.ActionBarView$HomeView" //
            )
        }, //
        {
            fileFromName("leak_asynctask_mpreview2.hprof"), //
            asList( //
                "com.example.leakcanary.MainActivity", //
                "android.widget.ActionMenuPresenter$OverflowMenuButton", //
                "android.widget.ImageButton" //
            )
        }, //
    });
  }

  private static File fileFromName(String filename) {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    URL url = classLoader.getResource(filename);
    return new File(url.getPath());
  }

  final File heapDumpFile;
  final List<String> expectedZombies;

  ExcludedRefs.Builder excludedRefs;

  public AutopsyTest(File heapDumpFile, List<String> expectedZombies) {
    this.heapDumpFile = heapDumpFile;
    this.expectedZombies = expectedZombies;
  }

  @Before public void setUp() {
    excludedRefs = new ExcludedRefs.Builder().clazz(WeakReference.class.getName(), true)
        .clazz("java.lang.ref.FinalizerReference", true);
  }

  @Test public void zombiesFound() {
    Autopsy autopsy = autopsy();
    assertZombies(autopsy, expectedZombies);
  }

  private void assertZombies(Autopsy autopsy, List<String> expectedZombies) {
    List<String> actualZombies = new ArrayList<>();
    for (LeakTrace leakTrace : autopsy.leakTraces) {
      List<LeakTraceElement> elements = leakTrace.elements;
      actualZombies.add(elements.get(elements.size() - 1).className);
    }
    Collections.sort(expectedZombies);
    Collections.sort(actualZombies);
    assertArrayEquals(expectedZombies.toArray(), actualZombies.toArray());
  }

  private Autopsy autopsy() {
    OOMAutopsy heapAnalyzer = new OOMAutopsy(excludedRefs.build(),
        asList(new ViewZombieMatcher(), new ActivityZombieMatcher()));
    Autopsy autopsy = heapAnalyzer.performAutopsy(heapDumpFile);
    System.out.println(autopsy);
    return autopsy;
  }
}
