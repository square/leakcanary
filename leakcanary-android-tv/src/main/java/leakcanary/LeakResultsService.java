/*
package leakcanary;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.text.format.Formatter;
import android.widget.Toast;

import com.squareup.leakcanary.AnalysisResult;
import com.squareup.leakcanary.DisplayLeakService;
import com.squareup.leakcanary.HeapDump;

public class LeakResultsService extends DisplayLeakService {
    private static final String MEMORY_LEAK = "MEMORY_LEAK";

    @Override
    protected void afterDefaultHandling(@NonNull HeapDump heapDump, @NonNull AnalysisResult result, @NonNull String leakInfo) {
        */
/*super.afterDefaultHandling(heapDump, result, leakInfo);
        if (!result.leakFound || result.excludedLeak) {
            return;
        }
        StringBuilder messageBuilder = new StringBuilder(result.className);
        if (MobiUtil.isValid(heapDump.referenceName)) {
            messageBuilder.append(" (")
                .append(heapDump.referenceName)
                .append(")");
        }
        messageBuilder.append(" has leaked:\n")
            .append(result.leakTrace.toString())
            .append("\n");
        MobiLog.getLog().e(MEMORY_LEAK, leakInfo);
        String leakedReference = heapDump.referenceName;
        String details = result.leakTrace.toDetailedString();
        String retainedHeapSize = Formatter.formatShortFileSize(getApplicationContext(), result.retainedHeapSize);
        Crashlytics.logException(new MemoryLeakException(leakedReference, details, retainedHeapSize, messageBuilder.toString()));
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(this, "Leak analysis complete! Leaked instance of: " + result.className, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                //Don't want to crash the process if toast failed for some reason
            }
        });*//*

    }
}
*/
