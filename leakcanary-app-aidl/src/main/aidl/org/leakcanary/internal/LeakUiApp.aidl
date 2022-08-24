package org.leakcanary.internal;

parcelable ParcelableHeapAnalysis;

interface LeakUiApp {
 void sendHeapAnalysis(in ParcelableHeapAnalysis heapAnalysis);
}
