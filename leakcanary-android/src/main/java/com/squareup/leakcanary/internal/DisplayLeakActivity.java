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
import com.squareup.leakcanary.CanaryLog;
import com.squareup.leakcanary.DefaultLeakDirectoryProvider;
import com.squareup.leakcanary.HeapDump;
import com.squareup.leakcanary.LeakDirectoryProvider;
import com.squareup.leakcanary.ResourceProvider;

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

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.text.format.Formatter.formatShortFileSize;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.squareup.leakcanary.BuildConfig.GIT_SHA;
import static com.squareup.leakcanary.BuildConfig.LIBRARY_VERSION;
import static com.squareup.leakcanary.LeakCanary.leakInfo;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.newSingleThreadExecutor;

@SuppressWarnings("ConstantConditions") @TargetApi(Build.VERSION_CODES.HONEYCOMB)
public final class DisplayLeakActivity extends Activity {

  private static LeakDirectoryProvider leakDirectoryProvider = null;

  private static final String SHOW_LEAK_EXTRA = "show_latest";

  public static PendingIntent createPendingIntent(Context context) {
    return createPendingIntent(context, null);
  }

  public static PendingIntent createPendingIntent(Context context, String referenceKey) {
    Intent intent = new Intent(context, DisplayLeakActivity.class);
    intent.putExtra(SHOW_LEAK_EXTRA, referenceKey);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    return PendingIntent.getActivity(context, 1, intent, FLAG_UPDATE_CURRENT);
  }

  public static void setLeakDirectoryProvider(LeakDirectoryProvider leakDirectoryProvider) {
    DisplayLeakActivity.leakDirectoryProvider = leakDirectoryProvider;
  }

  private static LeakDirectoryProvider leakDirectoryProvider(Context context) {
    LeakDirectoryProvider leakDirectoryProvider = DisplayLeakActivity.leakDirectoryProvider;
    if (leakDirectoryProvider == null) {
      leakDirectoryProvider = new DefaultLeakDirectoryProvider(context);
    }
    return leakDirectoryProvider;
  }

  // null until it's been first loaded.
  List<Leak> leaks;
  String visibleLeakRefKey;

  private ListView listView;
  private TextView failureView;
  private Button actionButton;

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

    setContentView(ResourceProvider.provider().leak_canary_display_leak());

    listView = (ListView) findViewById(ResourceProvider.provider().leak_canary_display_leak_list());
    failureView = (TextView) findViewById(ResourceProvider.provider().leak_canary_display_leak_failure());
    actionButton = (Button) findViewById(ResourceProvider.provider().leak_canary_action());

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
    LeakDirectoryProvider leakDirectoryProvider = leakDirectoryProvider(this);
    if (leakDirectoryProvider.isLeakStorageWritable()) {
      File leakDirectory = leakDirectoryProvider.leakDirectory();
      LoadLeaks.load(this, leakDirectory);
    } else {
      leakDirectoryProvider.requestPermission(this);
    }
  }

  @Override public void setTheme(int resid) {
    // We don't want this to be called with an incompatible theme.
    // This could happen if you implement runtime switching of themes
    // using ActivityLifecycleCallbacks.
    if (resid != ResourceProvider.provider().leak_canary_LeakCanary_Base()) {
      return;
    }
    super.setTheme(resid);
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    LoadLeaks.forgetActivity();
  }

  @Override public boolean onCreateOptionsMenu(Menu menu) {
    Leak visibleLeak = getVisibleLeak();
    if (visibleLeak != null) {
      menu.add(ResourceProvider.provider().leak_canary_share_leak())
          .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
              shareLeak();
              return true;
            }
          });
      if (visibleLeak.heapDump.heapDumpFile.exists()) {
        menu.add(ResourceProvider.provider().leak_canary_share_heap_dump())
            .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
              @Override public boolean onMenuItemClick(MenuItem item) {
                shareHeapDump();
                return true;
              }
            });
      }
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

  void shareLeak() {
    Leak visibleLeak = getVisibleLeak();
    String leakInfo = leakInfo(this, visibleLeak.heapDump, visibleLeak.result, true);
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_TEXT, leakInfo);
    startActivity(Intent.createChooser(intent, getString(ResourceProvider.provider().leak_canary_share_with())));
  }

  void shareHeapDump() {
    Leak visibleLeak = getVisibleLeak();
    File heapDumpFile = visibleLeak.heapDump.heapDumpFile;
    heapDumpFile.setReadable(true, false);
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("application/octet-stream");
    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(heapDumpFile));
    startActivity(Intent.createChooser(intent, getString(ResourceProvider.provider().leak_canary_share_with())));
  }

  void deleteVisibleLeak() {
    Leak visibleLeak = getVisibleLeak();
    File heapDumpFile = visibleLeak.heapDump.heapDumpFile;
    File resultFile = visibleLeak.resultFile;
    boolean resultDeleted = resultFile.delete();
    if (!resultDeleted) {
      CanaryLog.d("Could not delete result file %s", resultFile.getPath());
    }
    boolean heapDumpDeleted = heapDumpFile.delete();
    if (!heapDumpDeleted) {
      CanaryLog.d("Could not delete heap dump file %s", heapDumpFile.getPath());
    }
    visibleLeakRefKey = null;
    leaks.remove(visibleLeak);
    updateUi();
  }

  void deleteAllLeaks() {
    File leakDirectory = leakDirectoryProvider(DisplayLeakActivity.this).leakDirectory();
    File[] files = leakDirectory.listFiles();
    if (files != null) {
      for (File file : files) {
        boolean deleted = file.delete();
        if (!deleted) {
          CanaryLog.d("Could not delete file %s", file.getPath());
        }
      }
    }
    leaks = Collections.emptyList();
    updateUi();
  }

  void updateUi() {
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
        String failureMessage = getString(ResourceProvider.provider().leak_canary_failure_report())
            + LIBRARY_VERSION
            + " "
            + GIT_SHA
            + "\n"
            + Log.getStackTraceString(result.failure);
        failureView.setText(failureMessage);
        setTitle(ResourceProvider.provider().leak_canary_analysis_failed());
        invalidateOptionsMenu();
        getActionBar().setDisplayHomeAsUpEnabled(true);
        actionButton.setVisibility(VISIBLE);
        actionButton.setText(ResourceProvider.provider().leak_canary_delete());
        actionButton.setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View v) {
            deleteVisibleLeak();
          }
        });
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
          actionButton.setText(ResourceProvider.provider().leak_canary_delete());
          actionButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
              deleteVisibleLeak();
            }
          });
        }
        HeapDump heapDump = visibleLeak.heapDump;
        adapter.update(result.leakTrace, heapDump.referenceKey, heapDump.referenceName);
        String size = formatShortFileSize(this, result.retainedHeapSize);
        String className = classSimpleName(result.className);
        setTitle(getString(ResourceProvider.provider().leak_canary_class_has_leaked(), className, size));
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
        setTitle(getString(ResourceProvider.provider().leak_canary_leak_list_title(), getPackageName()));
        getActionBar().setDisplayHomeAsUpEnabled(false);
        actionButton.setText(ResourceProvider.provider().leak_canary_delete_all());
        actionButton.setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View v) {
            deleteAllLeaks();
          }
        });
      }
      actionButton.setVisibility(leaks.size() == 0 ? GONE : VISIBLE);
    }
  }

  Leak getVisibleLeak() {
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
            .inflate(ResourceProvider.provider().leak_canary_leak_row(), parent, false);
      }
      TextView titleView = (TextView) convertView.findViewById(ResourceProvider.provider().leak_canary_row_text());
      TextView timeView = (TextView) convertView.findViewById(ResourceProvider.provider().leak_canary_row_time());
      Leak leak = getItem(position);

      String index = (leaks.size() - position) + ". ";

      String title;
      if (leak.result.failure == null) {
        String className = classSimpleName(leak.result.className);
        String size = formatShortFileSize(DisplayLeakActivity.this, leak.result.retainedHeapSize);
        title = getString(ResourceProvider.provider().leak_canary_class_has_leaked(), className, size);
        if (leak.result.excludedLeak) {
          title = getString(ResourceProvider.provider().leak_canary_excluded_row(), title);
        }
        title = index + title;
      } else {
        title = index
            + leak.result.failure.getClass().getSimpleName()
            + " "
            + leak.result.failure.getMessage();
      }
      titleView.setText(title);
      String time =
          DateUtils.formatDateTime(DisplayLeakActivity.this, leak.resultFile.lastModified(),
              FORMAT_SHOW_TIME | FORMAT_SHOW_DATE);
      timeView.setText(time);
      return convertView;
    }
  }

  static class Leak {
    final HeapDump heapDump;
    final AnalysisResult result;
    final File resultFile;

    Leak(HeapDump heapDump, AnalysisResult result, File resultFile) {
      this.heapDump = heapDump;
      this.result = result;
      this.resultFile = resultFile;
    }
  }

  static class LoadLeaks implements Runnable {

    static final List<LoadLeaks> inFlight = new ArrayList<>();

    static final Executor backgroundExecutor = newSingleThreadExecutor("LoadLeaks");

    static void load(DisplayLeakActivity activity, File leakDirectory) {
      LoadLeaks loadLeaks = new LoadLeaks(activity, leakDirectory);
      inFlight.add(loadLeaks);
      backgroundExecutor.execute(loadLeaks);
    }

    static void forgetActivity() {
      for (LoadLeaks loadLeaks : inFlight) {
        loadLeaks.activityOrNull = null;
      }
      inFlight.clear();
    }

    DisplayLeakActivity activityOrNull;
    private final File leakDirectory;
    private final Handler mainHandler;

    LoadLeaks(DisplayLeakActivity activity, File leakDirectory) {
      this.activityOrNull = activity;
      this.leakDirectory = leakDirectory;
      mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override public void run() {
      final List<Leak> leaks = new ArrayList<>();
      File[] files = leakDirectory.listFiles(new FilenameFilter() {
        @Override public boolean accept(File dir, String filename) {
          return filename.endsWith(".result");
        }
      });

      if (files != null) {
        for (File resultFile : files) {
          FileInputStream fis = null;
          try {
            fis = new FileInputStream(resultFile);
            ObjectInputStream ois = new ObjectInputStream(fis);
            HeapDump heapDump = (HeapDump) ois.readObject();
            AnalysisResult result = (AnalysisResult) ois.readObject();
            leaks.add(new Leak(heapDump, result, resultFile));
          } catch (IOException | ClassNotFoundException e) {
            // Likely a change in the serializable result class.
            // Let's remove the files, we can't read them anymore.
            boolean deleted = resultFile.delete();
            if (deleted) {
              CanaryLog.d(e, "Could not read result file %s, deleted it.", resultFile);
            } else {
              CanaryLog.d(e, "Could not read result file %s, could not delete it either.",
                  resultFile);
            }
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
            return Long.valueOf(rhs.resultFile.lastModified())
                .compareTo(lhs.resultFile.lastModified());
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
