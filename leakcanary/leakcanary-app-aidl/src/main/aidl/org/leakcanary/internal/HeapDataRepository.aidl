package org.leakcanary.internal;

parcelable ParcelableDominators;

interface HeapDataRepository {
	ParcelableDominators sayHi(String heapDumpFilePath);
}
