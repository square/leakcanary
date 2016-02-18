package com.squareup.leakcanary.assistivetouch;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.squareup.leakcanary.AnalysisResult;
import com.squareup.leakcanary.CanaryLog;
import com.squareup.leakcanary.DefaultLeakDirectoryProvider;
import com.squareup.leakcanary.HeapDump;
import com.squareup.leakcanary.LeakDirectoryProvider;

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

import static com.squareup.leakcanary.internal.LeakCanaryInternals.newSingleThreadExecutor;
import static com.squareup.leakcanary.internal.LeakCanaryInternals.setEnabledBlocking;

public class AssistiveGuardService extends Service {

    public static final int MSG_REGISTER_CLIENT = 1 << 1;
    public static final int MSG_UNREGISTER_CLIENT = 1 << 2;
    public static final int MSG_DELETE_FILE = 1 << 3;
    public static final int MSG_RECEIVED = 1 << 4;
    public static final int MSG_RESULT_ANALYZED = 1 << 5;
    public static final int MSG_SHOULD_SHOW_NOTIFICATION = 1 << 6;
    public static final int MSG_SHOULD_SHOW_NOTIFICATION_RESULT = 1 << 7;

    private static final Executor initExecutor = newSingleThreadExecutor("AssistiveInit");

    private static AssistiveTouchWindow touchWindow;
    private static AssistivePanelWindow panelWindow;

    private static LeakDirectoryProvider leakDirectoryProvider = null;
    private List<Leak> mLeaks;

    //communicate with DisplayLeakActivity in different process
    /**
     * Keeps track of all current registered clients.
     */
    private final ArrayList<Messenger> mClients = new ArrayList<Messenger>();

    /**
     * Target we publish for clients to send messages to IncomingHandler.
     */
    final Messenger mMessenger = new Messenger(new IncomingHandler());

    /**
     * Handler of incoming messages from clients.
     */
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_CLIENT:
                    mClients.add(msg.replyTo);
                    break;
                case MSG_UNREGISTER_CLIENT:
                    mClients.remove(msg.replyTo);
                    break;
                case MSG_DELETE_FILE: {
                    LoadLeaks.load(AssistiveGuardService.this);
                    for (int i = mClients.size() - 1; i >= 0; i--) {
                        Message message = Message.obtain(null,
                                MSG_RECEIVED, 0, 0);
                        sendMsgQuitely(mClients.get(i), message);
                    }
                    break;
                }
                case MSG_RESULT_ANALYZED:
                    onResultAnalyzed();
                    break;
                case MSG_SHOULD_SHOW_NOTIFICATION:
                    for (int i = mClients.size() - 1; i >= 0; i--) {
                        Message message = Message.obtain(null,
                                MSG_SHOULD_SHOW_NOTIFICATION_RESULT, 0, 0);
                        message.arg1 = shouldShowNotification() ? 1 : 0;
                        sendMsgQuitely(mClients.get(i), message);
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    public void onResultAnalyzed() {
        LoadLeaks.load(this);
    }

    public boolean shouldShowNotification() {
        return getNotificationStatus();
    }

    public static void startAssistiveService(final Context context) {
        initExecutor.execute(new Runnable() {
            @Override
            public void run() {
                setEnabledBlocking(context, AssistiveGuardService.class, true);
                Intent intent = new Intent(context, AssistiveGuardService.class);
                context.startService(intent);
            }
        });
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        touchWindow = new AssistiveTouchWindow(getApplicationContext(), assistTContract);
        touchWindow.show();

        LoadLeaks.load(this);
    }

    @Override
    public void onDestroy() {
        if (touchWindow != null) {
            touchWindow.dismiss();
        }
        if (panelWindow != null) {
            panelWindow.dismiss();
        }

        touchWindow = null;
        panelWindow = null;

        super.onDestroy();
        LoadLeaks.forgetService();
    }

    private boolean putNotificationStatus(boolean status) {
        SharedPreferences sp = getSharedPreferences("leakcanary", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putBoolean("notification_status", status);
        return editor.commit();
    }

    private boolean getNotificationStatus() {
        SharedPreferences sp = getSharedPreferences("leakcanary", Context.MODE_PRIVATE);
        return sp.getBoolean("notification_status", true);
    }

    private void updateUi() {
        if (touchWindow != null) {
            touchWindow.updateNumDot(mLeaks == null ? 0 : mLeaks.size());
        }
    }

    private AssistiveTouchWindow.AssistiveTouchContract assistTContract =
            new AssistiveTouchWindow.AssistiveTouchContract() {
                @Override
                public void onClick() {
                    if (touchWindow != null) {
                        touchWindow.dismiss();
                    }

                    if (panelWindow == null) {
                        panelWindow = new AssistivePanelWindow(getApplicationContext(), assistPContract);
                    }
                    boolean status = getNotificationStatus();
                    panelWindow.show(status);
                }
            };

    private AssistivePanelWindow.AssistivePanelContract assistPContract =
            new AssistivePanelWindow.AssistivePanelContract() {
                @Override
                public void onDismiss() {
                    if (touchWindow != null) {
                        touchWindow.showAtLastLocation();
                    }
                }

                @Override
                public void onNotificationStatusChange(boolean status) {
                    putNotificationStatus(status);

                    if (!status) {
                        //Cancel a previously shown notification
                        NotificationManager notificationManager =
                                (NotificationManager) AssistiveGuardService.this.
                                        getSystemService(Context.NOTIFICATION_SERVICE);
                        notificationManager.cancel(0xDEAFBEEF);
                    }
                }

                @Override
                public void stopButtonOnClick() {
                    //dismiss window immediately
                    if (touchWindow != null) {
                        touchWindow.dismiss();
                    }
                    touchWindow = null;
                    if (panelWindow != null) {
                        panelWindow.dismiss();
                    }
                    panelWindow = null;

                    stopService();
                }
            };

    private void stopService() {
        stopSelf();
    }

    static File getLeakDirectory(Context context) {
        LeakDirectoryProvider leakDirectoryProvider = AssistiveGuardService.leakDirectoryProvider;
        if (leakDirectoryProvider != null) {
            return leakDirectoryProvider.leakDirectory();
        } else {
            return new DefaultLeakDirectoryProvider(context).leakDirectory();
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

        static void load(AssistiveGuardService service) {
            LoadLeaks loadLeaks = new LoadLeaks(service);
            inFlight.add(loadLeaks);
            backgroundExecutor.execute(loadLeaks);
        }

        static void forgetService() {
            for (LoadLeaks loadLeaks : inFlight) {
                loadLeaks.serviceOrNull = null;
            }
            inFlight.clear();
        }

        AssistiveGuardService serviceOrNull;
        private final File leakDirectory;
        private final Handler mainHandler;

        LoadLeaks(AssistiveGuardService service) {
            this.serviceOrNull = service;
            leakDirectory = getLeakDirectory(service);
            mainHandler = new Handler(Looper.getMainLooper());
        }

        @Override
        public void run() {
            final List<Leak> leaks = new ArrayList<>();
            File[] files = leakDirectory.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String filename) {
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
                    @Override
                    public int compare(Leak lhs, Leak rhs) {
                        return Long.valueOf(rhs.resultFile.lastModified())
                                .compareTo(lhs.resultFile.lastModified());
                    }
                });
            }
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    inFlight.remove(LoadLeaks.this);
                    if (serviceOrNull != null) {
                        serviceOrNull.mLeaks = leaks;
                        serviceOrNull.updateUi();
                    }
                }
            });
        }
    }

    public static void sendMsgQuitely(Messenger messenger, Message msg) {
        try {
            messenger.send(msg);
        } catch (RemoteException ignored) {
        }
    }
}
