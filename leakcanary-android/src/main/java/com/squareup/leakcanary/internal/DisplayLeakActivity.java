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

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
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
import com.squareup.leakcanary.AnalyzedHeap;
import com.squareup.leakcanary.CanaryLog;
import com.squareup.leakcanary.HeapDump;
import com.squareup.leakcanary.LeakDirectoryProvider;
import com.squareup.leakcanary.R;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.support.v4.content.FileProvider.getUriForFile;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;
import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.text.format.Formatter.formatShortFileSize;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.squareup.leakcanary.BuildConfig.GIT_SHA;
import static com.squareup.leakcanary.BuildConfig.LIBRARY_VERSION;
import static com.squareup.leakcanary.LeakCanary.leakInfo;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.getLeakDirectoryProvider;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.newSingleThreadExecutor;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.setEnabledBlocking;

@SuppressWarnings("ConstantConditions")
public final class DisplayLeakActivity extends Activity {

  private static final String SHOW_LEAK_EXTRA = "show_latest";

  // Public API.
  @SuppressWarnings("unused")
  public static PendingIntent createPendingIntent(Context context) {
    return createPendingIntent(context, null);
  }

  public static PendingIntent createPendingIntent(Context context, String referenceKey) {
    setEnabledBlocking(context, DisplayLeakActivity.class, true);
    Intent intent = new Intent(context, DisplayLeakActivity.class);
    intent.putExtra(SHOW_LEAK_EXTRA, referenceKey);
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    return PendingIntent.getActivity(context, 1, intent, FLAG_UPDATE_CURRENT);
  }

  // null until it's been first loaded.
  List<AnalyzedHeap> leaks;
  String visibleLeakRefKey;

  private ListView listView;
  private TextView failureView;
  private Button actionButton;

  @SuppressWarnings("unchecked")
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

    leaks = (List<AnalyzedHeap>) getLastNonConfigurationInstance();

    setContentView(R.layout.leak_canary_display_leak);

    listView = findViewById(R.id.leak_canary_display_leak_list);
    failureView = findViewById(R.id.leak_canary_display_leak_failure);
    actionButton = findViewById(R.id.leak_canary_action);

    updateUi();
  }

  @Override public Object onRetainNonConfigurationInstance() {
    return leaks;
  }

  @Override protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString("visibleLeakRefKey", visibleLeakRefKey);
  }

  @Override protected void onResume() {
    super.onResume();
    LoadLeaks.load(this, getLeakDirectoryProvider(this));
  }

  @Override public void setTheme(int resid) {
    // We don't want this to be called with an incompatible theme.
    // This could happen if you implement runtime switching of themes
    // using ActivityLifecycleCallbacks.
    if (resid != R.style.leak_canary_LeakCanary_Base) {
      return;
    }
    super.setTheme(resid);
  }

  @Override protected void onDestroy() {
    super.onDestroy();
    LoadLeaks.forgetActivity();
  }

  @Override public boolean onCreateOptionsMenu(Menu menu) {
    AnalyzedHeap visibleLeak = getVisibleLeak();
    if (visibleLeak != null) {
      menu.add(R.string.leak_canary_share_leak)
          .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) {
              shareLeak();
              return true;
            }
          });
      if (visibleLeak.heapDumpFileExists) {
        menu.add(R.string.leak_canary_share_heap_dump)
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
    AnalyzedHeap visibleLeak = getVisibleLeak();
    String leakInfo = leakInfo(this, visibleLeak.heapDump, visibleLeak.result, true);
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_TEXT, leakInfo);
    startActivity(Intent.createChooser(intent, getString(R.string.leak_canary_share_with)));
  }

  @SuppressLint("SetWorldReadable")
  void shareHeapDump() {
    AnalyzedHeap visibleLeak = getVisibleLeak();
    final File heapDumpFile = visibleLeak.heapDump.heapDumpFile;
    AsyncTask.SERIAL_EXECUTOR.execute(new Runnable() {
      @Override public void run() {
        //noinspection ResultOfMethodCallIgnored
        heapDumpFile.setReadable(true, false);
        final Uri heapDumpUri = getUriForFile(getBaseContext(),
            "com.squareup.leakcanary.fileprovider." + getApplication().getPackageName(),
            heapDumpFile);
        runOnUiThread(new Runnable() {
          @Override public void run() {
            startShareIntentChooser(heapDumpUri);
          }
        });
      }
    });
  }

  private void startShareIntentChooser(Uri heapDumpUri) {
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("application/octet-stream");
    intent.putExtra(Intent.EXTRA_STREAM, heapDumpUri);
    startActivity(Intent.createChooser(intent, getString(R.string.leak_canary_share_with)));
  }

  void deleteVisibleLeak() {
    final AnalyzedHeap visibleLeak = getVisibleLeak();
    AsyncTask.SERIAL_EXECUTOR.execute(new Runnable() {
      @Override public void run() {
        File heapDumpFile = visibleLeak.heapDump.heapDumpFile;
        File resultFile = visibleLeak.selfFile;
        boolean resultDeleted = resultFile.delete();
        if (!resultDeleted) {
          CanaryLog.d("Could not delete result file %s", resultFile.getPath());
        }
        boolean heapDumpDeleted = heapDumpFile.delete();
        if (!heapDumpDeleted) {
          CanaryLog.d("Could not delete heap dump file %s", heapDumpFile.getPath());
        }
      }
    });
    visibleLeakRefKey = null;
    leaks.remove(visibleLeak);
    updateUi();
  }

  void deleteAllLeaks() {
    final LeakDirectoryProvider leakDirectoryProvider = getLeakDirectoryProvider(this);
    AsyncTask.SERIAL_EXECUTOR.execute(new Runnable() {
      @Override public void run() {
        leakDirectoryProvider.clearLeakDirectory();
      }
    });
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

    final AnalyzedHeap visibleLeak = getVisibleLeak();
    if (visibleLeak == null) {
      visibleLeakRefKey = null;
    }

    ListAdapter listAdapter = listView.getAdapter();
    // Reset to defaults
    listView.setVisibility(VISIBLE);
    failureView.setVisibility(GONE);

    if (visibleLeak != null) {
      AnalysisResult result = visibleLeak.result;
      actionButton.setVisibility(VISIBLE);
      actionButton.setText(R.string.leak_canary_delete);
      actionButton.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View v) {
          deleteVisibleLeak();
        }
      });
      invalidateOptionsMenu();
      setDisplayHomeAsUpEnabled(true);

      if (result.leakFound) {
        final DisplayLeakAdapter adapter = new DisplayLeakAdapter(getResources());
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
          @Override
          public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            adapter.toggleRow(position);
          }
        });
        HeapDump heapDump = visibleLeak.heapDump;
        adapter.update(result.leakTrace, heapDump.referenceKey, heapDump.referenceName);
        if (result.retainedHeapSize == AnalysisResult.RETAINED_HEAP_SKIPPED) {
          String className = classSimpleName(result.className);
          setTitle(getString(R.string.leak_canary_class_has_leaked, className));
        } else {
          String size = formatShortFileSize(this, result.retainedHeapSize);
          String className = classSimpleName(result.className);
          setTitle(getString(R.string.leak_canary_class_has_leaked_retaining, className, size));
        }
      } else {
        listView.setVisibility(GONE);
        failureView.setVisibility(VISIBLE);
        listView.setAdapter(null);

        String failureMessage;
        if (result.failure != null) {
          setTitle(R.string.leak_canary_analysis_failed);
          failureMessage = getString(R.string.leak_canary_failure_report)
              + LIBRARY_VERSION
              + " "
              + GIT_SHA
              + "\n"
              + Log.getStackTraceString(result.failure);
        } else {
          String className = classSimpleName(result.className);
          setTitle(getString(R.string.leak_canary_class_no_leak, className));
          failureMessage = getString(R.string.leak_canary_no_leak_details);
        }
        String path = visibleLeak.heapDump.heapDumpFile.getAbsolutePath();
        failureMessage += "\n\n" + getString(R.string.leak_canary_download_dump, path);
        failureView.setText(failureMessage);
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
        setTitle(getString(R.string.leak_canary_leak_list_title, getPackageName()));
        setDisplayHomeAsUpEnabled(false);
        actionButton.setText(R.string.leak_canary_delete_all);
        actionButton.setOnClickListener(new View.OnClickListener() {
          @Override public void onClick(View v) {
            new AlertDialog.Builder(DisplayLeakActivity.this).setIcon(
                android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.leak_canary_delete_all)
                .setMessage(R.string.leak_canary_delete_all_leaks_title)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                  @Override public void onClick(DialogInterface dialog, int which) {
                    deleteAllLeaks();
                  }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
          }
        });
      }
      actionButton.setVisibility(leaks.size() == 0 ? GONE : VISIBLE);
    }
  }

  private void setDisplayHomeAsUpEnabled(boolean enabled) {
    ActionBar actionBar = getActionBar();
    if (actionBar == null) {
      // https://github.com/square/leakcanary/issues/967
      return;
    }
    actionBar.setDisplayHomeAsUpEnabled(enabled);
  }

  AnalyzedHeap getVisibleLeak() {
    if (leaks == null) {
      return null;
    }
    for (AnalyzedHeap leak : leaks) {
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

    @Override public AnalyzedHeap getItem(int position) {
      return leaks.get(position);
    }

    @Override public long getItemId(int position) {
      return position;
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
      if (convertView == null) {
        convertView = LayoutInflater.from(DisplayLeakActivity.this)
            .inflate(R.layout.leak_canary_leak_row, parent, false);
      }
      TextView titleView = convertView.findViewById(R.id.leak_canary_row_text);
      TextView timeView = convertView.findViewById(R.id.leak_canary_row_time);
      AnalyzedHeap leak = getItem(position);

      String index = (leaks.size() - position) + ". ";

      String title;
      if (leak.result.failure != null) {
        title = index
            + leak.result.failure.getClass().getSimpleName()
            + " "
            + leak.result.failure.getMessage();
      } else {
        String className = classSimpleName(leak.result.className);
        if (leak.result.leakFound) {
          if (leak.result.retainedHeapSize == AnalysisResult.RETAINED_HEAP_SKIPPED) {
            title = getString(R.string.leak_canary_class_has_leaked, className);
          } else {
            String size = formatShortFileSize(DisplayLeakActivity.this,
                leak.result.retainedHeapSize);
            title = getString(R.string.leak_canary_class_has_leaked_retaining, className, size);
          }
          if (leak.result.excludedLeak) {
            title = getString(R.string.leak_canary_excluded_row, title);
          }
          title = index + title;
        } else {
          title = index + getString(R.string.leak_canary_class_no_leak, className);
        }
      }
      titleView.setText(title);
      String time =
          DateUtils.formatDateTime(DisplayLeakActivity.this, leak.selfLastModified,
              FORMAT_SHOW_TIME | FORMAT_SHOW_DATE);
      timeView.setText(time);
      return convertView;
    }
  }

  static class LoadLeaks implements Runnable {

    static final List<LoadLeaks> inFlight = new ArrayList<>();

    static final Executor backgroundExecutor = newSingleThreadExecutor("LoadLeaks");

    static void load(DisplayLeakActivity activity, LeakDirectoryProvider leakDirectoryProvider) {
      LoadLeaks loadLeaks = new LoadLeaks(activity, leakDirectoryProvider);
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
    private final LeakDirectoryProvider leakDirectoryProvider;
    private final Handler mainHandler;

    LoadLeaks(DisplayLeakActivity activity, LeakDirectoryProvider leakDirectoryProvider) {
      this.activityOrNull = activity;
      this.leakDirectoryProvider = leakDirectoryProvider;
      mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override public void run() {
      final List<AnalyzedHeap> leaks = new ArrayList<>();
      List<File> files = leakDirectoryProvider.listFiles(new FilenameFilter() {
        @Override public boolean accept(File dir, String filename) {
          return filename.endsWith(".result");
        }
      });
      for (File resultFile : files) {
        final AnalyzedHeap leak = AnalyzedHeap.load(resultFile);
        if (leak != null) {
          leaks.add(leak);
        }
      }
      Collections.sort(leaks, new Comparator<AnalyzedHeap>() {
        @Override public int compare(AnalyzedHeap lhs, AnalyzedHeap rhs) {
          return Long.valueOf(rhs.selfFile.lastModified())
              .compareTo(lhs.selfFile.lastModified());
        }
      });
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
