package com.squareup.leakcanary.assistivetouch;

import android.content.Context;
import android.graphics.Point;
import android.util.DisplayMetrics;

public class ScreenUtil {
    private ScreenUtil() {
    }

    public static int getStatusBarHeight(Context context) {
        int result = 0;
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = context.getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    public static Point getScreenSize(Context context) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        return new Point(width, height);
    }
}
