package com.squareup.leakcanary.assistivetouch;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;

import com.squareup.leakcanary.AbstractAnalysisResultService;
import com.squareup.leakcanary.AnalysisResult;
import com.squareup.leakcanary.CanaryLog;
import com.squareup.leakcanary.HeapDump;
import com.squareup.leakcanary.R;
import com.squareup.leakcanary.internal.DisplayLeakActivity;
import com.squareup.leakcanary.internal.LeakCanaryInternals;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

import static android.text.format.Formatter.formatShortFileSize;
import static com.squareup.leakcanary.LeakCanary.leakInfo;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.classSimpleName;

public class AssistiveResultService extends AbstractAnalysisResultService {
    private boolean isBind;
    private final Object wakeLock = new Object();
    private boolean shouldShowNotification = true;
    private Messenger mService = null;
    private final Messenger mMessenger = new Messenger(new IncomingHandler());

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AssistiveGuardService.MSG_RECEIVED:
                    break;
                case AssistiveGuardService.MSG_SHOULD_SHOW_NOTIFICATION_RESULT:
                    shouldShowNotification = msg.arg1 == 1;
                    synchronized (wakeLock) {
                        wakeLock.notifyAll();
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder iBinder) {
            mService = new Messenger(iBinder);

            Message msg = Message.obtain(null,
                    AssistiveGuardService.MSG_REGISTER_CLIENT);
            msg.replyTo = mMessenger;
            AssistiveGuardService.sendMsgQuitely(mService, msg);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        bindGuardService();
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        unbindGuardService();
        super.onDestroy();
    }

    public void bindGuardService() {
        Intent intent = new Intent(this, AssistiveGuardService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        isBind = true;
    }

    public void unbindGuardService() {
        if (isBind) {
            if (mService != null) {
                Message msg = Message.obtain(null,
                        AssistiveGuardService.MSG_UNREGISTER_CLIENT);
                msg.replyTo = mMessenger;
                AssistiveGuardService.sendMsgQuitely(mService, msg);
            }
            unbindService(connection);
            isBind = false;
        }
    }

    @Override
    protected void onHeapAnalyzed(HeapDump heapDump, AnalysisResult result) {
        String leakInfo = leakInfo(this, heapDump, result, true);
        CanaryLog.d(leakInfo);

        boolean resultSaved = false;
        boolean shouldSaveResult = result.leakFound || result.failure != null;
        if (shouldSaveResult) {
            heapDump = renameHeapdump(heapDump);
            resultSaved = saveResult(heapDump, result);
        }

        PendingIntent pendingIntent;
        String contentTitle;
        String contentText;

        if (!shouldSaveResult) {
            contentTitle = getString(R.string.leak_canary_no_leak_title);
            contentText = getString(R.string.leak_canary_no_leak_text);
            pendingIntent = null;
        } else if (resultSaved) {
            pendingIntent = DisplayLeakActivity.createPendingIntent(this, heapDump.referenceKey);

            if (result.failure == null) {
                String size = formatShortFileSize(this, result.retainedHeapSize);
                String className = classSimpleName(result.className);
                if (result.excludedLeak) {
                    contentTitle = getString(R.string.leak_canary_leak_excluded, className, size);
                } else {
                    contentTitle = getString(R.string.leak_canary_class_has_leaked, className, size);
                }
            } else {
                contentTitle = getString(R.string.leak_canary_analysis_failed);
            }
            contentText = getString(R.string.leak_canary_notification_message);

            Message msg = Message.obtain(null,
                    AssistiveGuardService.MSG_RESULT_ANALYZED, 0, 0);
            AssistiveGuardService.sendMsgQuitely(mService, msg);
        } else {
            contentTitle = getString(R.string.leak_canary_could_not_save_title);
            contentText = getString(R.string.leak_canary_could_not_save_text);
            pendingIntent = null;
        }

        Message msg = Message.obtain(null,
                AssistiveGuardService.MSG_SHOULD_SHOW_NOTIFICATION, 0, 0);
        AssistiveGuardService.sendMsgQuitely(mService, msg);

        //wait until send result to guard service
        synchronized (wakeLock) {
            try {
                wakeLock.wait(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                wakeLock.notifyAll();
            }
        }

        if (shouldShowNotification) {
            LeakCanaryInternals.showNotification(this, contentTitle, contentText, pendingIntent);
        }
    }

    private boolean saveResult(HeapDump heapDump, AnalysisResult result) {
        File resultFile = new File(heapDump.heapDumpFile.getParentFile(),
                heapDump.heapDumpFile.getName() + ".result");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(resultFile);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(heapDump);
            oos.writeObject(result);
            return true;
        } catch (IOException e) {
            CanaryLog.d(e, "Could not save leak analysis result to disk.");
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    private HeapDump renameHeapdump(HeapDump heapDump) {
        String fileName =
                new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS'.hprof'", Locale.US).format(new Date());

        File newFile = new File(heapDump.heapDumpFile.getParent(), fileName);
        boolean renamed = heapDump.heapDumpFile.renameTo(newFile);
        if (!renamed) {
            CanaryLog.d("Could not rename heap dump file %s to %s", heapDump.heapDumpFile.getPath(),
                    newFile.getPath());
        }
        heapDump =
                new HeapDump(newFile, heapDump.referenceKey, heapDump.referenceName, heapDump.excludedRefs,
                        heapDump.watchDurationMs, heapDump.gcDurationMs, heapDump.heapDumpDurationMs);

        Resources resources = getResources();
        int maxStoredHeapDumps =
                Math.max(resources.getInteger(R.integer.leak_canary_max_stored_leaks), 1);
        File[] hprofFiles = heapDump.heapDumpFile.getParentFile().listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String filename) {
                return filename.endsWith(".hprof");
            }
        });

        if (hprofFiles.length > maxStoredHeapDumps) {
            // Sort with oldest modified first.
            Arrays.sort(hprofFiles, new Comparator<File>() {
                @Override
                public int compare(File lhs, File rhs) {
                    return Long.valueOf(lhs.lastModified()).compareTo(rhs.lastModified());
                }
            });
            boolean deleted = hprofFiles[0].delete();
            if (!deleted) {
                CanaryLog.d("Could not delete old hprof file %s", hprofFiles[0].getPath());
            }
        }
        return heapDump;
    }
}
