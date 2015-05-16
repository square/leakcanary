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
package com.squareup.leakcanary.internal;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import com.squareup.leakcanary.AnalysisResult;
import com.squareup.leakcanary.HeapDump;
import com.squareup.leakcanary.R;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.squareup.leakcanary.LeakCanary.leakInfo;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.detectedLeakDirectory;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.leakResultFile;

@SuppressWarnings("ConstantConditions") @TargetApi(Build.VERSION_CODES.HONEYCOMB)
public final class DisplayLeakActivity extends Activity {

  private static final String TAG = "DisplayLeakActivity";
  private static final String SHOW_LEAK_EXTRA = "show_latest";

  public static PendingIntent createPendingIntent(Context context, String referenceKey) {
    Intent intent = new Intent(context, DisplayLeakActivity.class);
    intent.putExtra(SHOW_LEAK_EXTRA, referenceKey);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    return PendingIntent.getActivity(context, 1, intent, FLAG_UPDATE_CURRENT);
  }

  // null until it's been first loaded.
  private List<Leak> leaks;
  private String visibleLeakRefKey;

  private ListView listView;
  private TextView failureView;
  private Button actionButton;
  private int maxStoredLeaks;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (savedInstanceState != null) {
      visibleLeakRefKey = savedInstanceState.getString("visibleLeakRefKey");
    } else {
      Intent intent = getIntent();
      if (intent.hasExtra(SHOW_LEAK_EXTRA)) {
        visibleLeakRefKey = intent.getStringExtra(SHOW_LEAK_EXTRA);
      }
    }

    //noinspection unchecked
    leaks = (List<Leak>) getLastNonConfigurationInstance();

    setContentView(R.layout.__leak_canary_display_leak);

    listView = (ListView) findViewById(R.id.__leak_canary_display_leak_list);
    failureView = (TextView) findViewById(R.id.__leak_canary_display_leak_failure);
    actionButton = (Button) findViewById(R.id.__leak_canary_action);

    maxStoredLeaks = getResources().getInteger(R.integer.__leak_canary_max_stored_leaks);

    updateUi();
  }

  // No, it's not deprecated. Android lies.
  @Override public Object onRetainNonConfigurationInstance() {
    return leaks;
  }

  @Override protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString("visibleLeakRefKey", visibleLeakRefKey);
  }

  @Override protected void onResume() {
    super.onResume();
    LoadLeaks.load(this);
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    LoadLeaks.forgetActivity();
  }

  @Override public boolean onCreateOptionsMenu(Menu menu) {
    if (getVisibleLeak() != null) {
      menu.add(R.string.__leak_canary_share_leak)
          .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
              shareLeak();
              return true;
            }
          });
      menu.add(R.string.__leak_canary_share_heap_dump)
          .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
              shareHeapDump();
              return true;
            }
          });
      return true;
    }
    return false;
  }

  @Override public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      visibleLeakRefKey = null;
      updateUi();
    }
    return true;
  }

  @Override public void onBackPressed() {
    if (visibleLeakRefKey != null) {
      visibleLeakRefKey = null;
      updateUi();
    } else {
      super.onBackPressed();
    }
  }

  private void shareLeak() {
    Leak visibleLeak = getVisibleLeak();
    String leakInfo = leakInfo(this, visibleLeak.heapDump, visibleLeak.result, true);
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_TEXT, leakInfo);
    startActivity(Intent.createChooser(intent, getString(R.string.__leak_canary_share_with)));
  }

  private void shareHeapDump() {
    Leak visibleLeak = getVisibleLeak();
    File heapDumpFile = visibleLeak.heapDump.heapDumpFile;
    heapDumpFile.setReadable(true, false);
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("application/octet-stream");
    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(heapDumpFile));
    startActivity(Intent.createChooser(intent, getString(R.string.__leak_canary_share_with)));
  }

  private void updateUi() {
    if (leaks == null) {
      setTitle("Loading leaks...");
      return;
    }
    if (leaks.isEmpty()) {
      visibleLeakRefKey = null;
    }

    final Leak visibleLeak = getVisibleLeak();
    if (visibleLeak == null) {
      visibleLeakRefKey = null;
    }

    ListAdapter listAdapter = listView.getAdapter();
    // Reset to defaults
    listView.setVisibility(VISIBLE);
    failureView.setVisibility(GONE);

    if (visibleLeak != null) {
      AnalysisResult result = visibleLeak.result;
      if (result.failure != null) {
        listView.setVisibility(GONE);
        failureView.setVisibility(VISIBLE);
        failureView.setText(
            getString(R.string.__leak_canary_failure_report) + Log.getStackTraceString(
                result.failure));
        setTitle(R.string.__leak_canary_analysis_failed);
        invalidateOptionsMenu();
        getActionBar().setDisplayHomeAsUpEnabled(true);
        actionButton.setVisibility(VISIBLE);
        actionButton.setText(R.string.__leak_canary_delete);
        listView.setAdapter(null);
      } else {
        final DisplayLeakAdapter adapter;
        if (listAdapter instanceof DisplayLeakAdapter) {
          adapter = (DisplayLeakAdapter) listAdapter;
        } else {
          adapter = new DisplayLeakAdapter();
          listView.setAdapter(adapter);
          listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
              adapter.toggleRow(position);
            }
          });
          invalidateOptionsMenu();
          getActionBar().setDisplayHomeAsUpEnabled(true);
          actionButton.setVisibility(VISIBLE);
          actionButton.setText(R.string.__leak_canary_delete);
          actionButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
              Leak visibleLeak = getVisibleLeak();
              File resultFile = leakResultFile(visibleLeak.heapDump.heapDumpFile);
              resultFile.delete();
              visibleLeak.heapDump.heapDumpFile.delete();
              visibleLeakRefKey = null;
              leaks.remove(visibleLeak);
              updateUi();
            }
          });
        }
        HeapDump heapDump = visibleLeak.heapDump;
        adapter.update(result.leakTrace, heapDump.referenceKey, heapDump.referenceName);
        setTitle(
            getString(R.string.__leak_canary_class_has_leaked, classSimpleName(result.className)));
      }
    } else {
      if (listAdapter instanceof LeakListAdapter) {
        ((LeakListAdapter) listAdapter).notifyDataSetChanged();
      } else {
        LeakListAdapter adapter = new LeakListAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
          @Override
          public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            visibleLeakRefKey = leaks.get(position).heapDump.referenceKey;
            updateUi();
          }
        });
        invalidateOptionsMenu();
        setTitle(getString(R.string.__leak_canary_leak_list_title, getPackageName()));
        getActionBar().setDisplayHomeAsUpEnabled(false);
        actionButton.setText(R.string.__leak_canary_delete_all);
        actionButton.setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View v) {
            File[] files = detectedLeakDirectory().listFiles();
            if (files != null) {
              for (File file : files) {
                file.delete();
              }
            }
            leaks = Collections.emptyList();
            updateUi();
          }
        });
      }
      actionButton.setVisibility(leaks.size() == 0 ? GONE : VISIBLE);
    }
  }

  private Leak getVisibleLeak() {
    if (leaks == null) {
      return null;
    }
    for (Leak leak : leaks) {
      if (leak.heapDump.referenceKey.equals(visibleLeakRefKey)) {
        return leak;
      }
    }
    return null;
  }

  class LeakListAdapter extends BaseAdapter {

    @Override public int getCount() {
      return leaks.size();
    }

    @Override public Leak getItem(int position) {
      return leaks.get(position);
    }

    @Override public long getItemId(int position) {
      return position;
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
      if (convertView == null) {
        convertView = LayoutInflater.from(DisplayLeakActivity.this)
            .inflate(R.layout.__leak_canary_leak_row, parent, false);
      }
      TextView titleView = (TextView) convertView.findViewById(R.id.__leak_canary_row_text);
      TextView timeView = (TextView) convertView.findViewById(R.id.__leak_canary_row_time);
      Leak leak = getItem(position);

      String index;
      if (position == 0 && leaks.size() == maxStoredLeaks) {
        index = "MAX. ";
      } else {
        index = (leaks.size() - position) + ". ";
      }

      String title;
      if (leak.result.failure == null) {
        title = index + getString(R.string.__leak_canary_class_has_leaked,
            classSimpleName(leak.result.className));
      } else {
        title = index
            + leak.result.failure.getClass().getSimpleName()
            + " "
            + leak.result.failure.getMessage();
      }
      titleView.setText(title);
      String time = DateUtils.formatDateTime(DisplayLeakActivity.this,
          leak.heapDump.heapDumpFile.lastModified(), FORMAT_SHOW_TIME | FORMAT_SHOW_DATE);
      timeView.setText(time);
      return convertView;
    }
  }

  static class Leak {
    final HeapDump heapDump;
    final AnalysisResult result;

    Leak(HeapDump heapDump, AnalysisResult result) {
      this.heapDump = heapDump;
      this.result = result;
    }
  }

  static class LoadLeaks implements Runnable {

    static final List<LoadLeaks> inFlight = new ArrayList<>();

    static final Executor backgroundExecutor = Executors.newSingleThreadExecutor();

    static void load(DisplayLeakActivity activity) {
      LoadLeaks loadLeaks = new LoadLeaks(activity);
      inFlight.add(loadLeaks);
      backgroundExecutor.execute(loadLeaks);
    }

    static void forgetActivity() {
      for (LoadLeaks loadLeaks : inFlight) {
        loadLeaks.activityOrNull = null;
      }
      inFlight.clear();
    }

    private DisplayLeakActivity activityOrNull;
    private final File leakDirectory;
    private final Handler mainHandler;

    LoadLeaks(DisplayLeakActivity activity) {
      this.activityOrNull = activity;
      leakDirectory = detectedLeakDirectory();
      mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override public void run() {
      final List<Leak> leaks = new ArrayList<>();
      File[] files = leakDirectory.listFiles(new FilenameFilter() {
        @Override public boolean accept(File dir, String filename) {
          return filename.endsWith(".hprof");
        }
      });
      if (files != null) {
        for (File heapDumpFile : files) {
          File resultFile = leakResultFile(heapDumpFile);
          FileInputStream fis = null;
          try {
            fis = new FileInputStream(resultFile);
            ObjectInputStream ois = new ObjectInputStream(fis);
            HeapDump heapDump = (HeapDump) ois.readObject();
            AnalysisResult result = (AnalysisResult) ois.readObject();
            leaks.add(new Leak(heapDump, result));
          } catch (IOException | ClassNotFoundException e) {
            // Likely a change in the serializable result class.
            // Let's remove the files, we can't read them anymore.
            heapDumpFile.delete();
            resultFile.delete();
            Log.e(TAG, "Could not read result file, deleted result and heap dump:" + heapDumpFile,
                e);
          } finally {
            if (fis != null) {
              try {
                fis.close();
              } catch (IOException ignored) {
              }
            }
          }
        }
        Collections.sort(leaks, new Comparator<Leak>() {
          @Override public int compare(Leak lhs, Leak rhs) {
            return Long.valueOf(rhs.heapDump.heapDumpFile.lastModified())
                .compareTo(lhs.heapDump.heapDumpFile.lastModified());
          }
        });
      }
      mainHandler.post(new Runnable() {
        @Override public void run() {
          inFlight.remove(LoadLeaks.this);
          if (activityOrNull != null) {
            activityOrNull.leaks = leaks;
            activityOrNull.updateUi();
          }
        }
      });
    }
  }

  static String classSimpleName(String className) {
    int separator = className.lastIndexOf('.');
    if (separator == -1) {
      return className;
    } else {
      return className.substring(separator + 1);
    }
  }
}
