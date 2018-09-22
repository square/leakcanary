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
package com.example.leakcanary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Toast;

public class MainActivity extends Activity {

  private ChainedReference<HttpRequestHelper> httpRequestHelper;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main_activity);

    View button = findViewById(R.id.async_work);
    button.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        startAsyncWork();
      }
    });

    findViewById(R.id.grow_analysis_size).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        growAnalysisSize();
      }
    });

    httpRequestHelper = (ChainedReference<HttpRequestHelper>) getLastNonConfigurationInstance();
    if (httpRequestHelper == null) {
      httpRequestHelper = new ChainedReference<>(new HttpRequestHelper(button), null);
    }
  }

  // This method makes long reference chain to grow AnalysisReport memory footprint.
  private void growAnalysisSize() {
    for (int i = 0; i< 1000; i++) {
      httpRequestHelper = new ChainedReference<>(null, httpRequestHelper);
    }
    Toast.makeText(this, R.string.grown_successfully, Toast.LENGTH_SHORT).show();
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return httpRequestHelper;
  }

  @SuppressLint("StaticFieldLeak")
  void startAsyncWork() {
    // This runnable is an anonymous class and therefore has a hidden reference to the outer
    // class MainActivity. If the activity gets destroyed before the thread finishes (e.g. rotation),
    // the activity instance will leak.
    Runnable work = new Runnable() {
      @Override
      public void run() {
        // Do some slow work in background
        SystemClock.sleep(20000);
      }
    };
    new Thread(work).start();
  }

  public static class ChainedReference<T> {
    private final T data;
    private final ChainedReference<T> next;

    public ChainedReference(T data, ChainedReference<T> reference) {
      this.data = data;
      this.next = reference;
    }

    public T getData() {
      return data;
    }
  }
}