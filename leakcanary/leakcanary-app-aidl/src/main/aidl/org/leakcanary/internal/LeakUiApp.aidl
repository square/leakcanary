package org.leakcanary.internal;

import android.net.Uri;

parcelable ParcelableHeapAnalysis;

interface LeakUiApp {
 void sendHeapAnalysis(in ParcelableHeapAnalysis heapAnalysis, in Uri heapDumpUri);
}
