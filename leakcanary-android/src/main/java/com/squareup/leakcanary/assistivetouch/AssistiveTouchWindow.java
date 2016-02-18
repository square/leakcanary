package com.squareup.leakcanary.assistivetouch;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.squareup.leakcanary.R;

public class AssistiveTouchWindow extends GestureDetector.SimpleOnGestureListener implements View.OnTouchListener {
    public static final int DEFAULT_OFFSET_X = 0;
    public static final int DEFAULT_OFFSET_Y = 0;
    public static final float MOVING_ALPHA = .5F;

    private final int statusBarHeight;
    private final Context context;
    private final WindowManager wm;
    private final GestureDetector gestureDetector;
    private View assistiveTouchView;
    private final AssistiveTouchContract contract;

    private int lastX, lastY;
    private boolean moveFlag;

    public AssistiveTouchWindow(Context context, AssistiveTouchContract contract) {
        this.context = context;
        this.contract = contract;
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        gestureDetector = new GestureDetector(context, this);
        statusBarHeight = ScreenUtil.getStatusBarHeight(context);
    }

    public void show() {
        showAtLocation(DEFAULT_OFFSET_X, DEFAULT_OFFSET_Y);
    }

    public void showAtLastLocation() {
        showAtLocation(lastX, lastY);
    }

    /**
     * show at location, the x, y is the offset of the origin(0, status bar height)
     *
     * @param x
     * @param y
     */
    public void showAtLocation(int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                x,
                y,
                WindowManager.LayoutParams.TYPE_TOAST,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
//                          |  WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,//lock the location
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.alpha = MOVING_ALPHA;

        View view = getAssistiveTouchView();

        wm.addView(view, p);

        lastX = x;
        lastY = y;
    }

    public void dismiss() {
        if (assistiveTouchView != null && assistiveTouchView.getWindowToken() != null) {
            wm.removeView(assistiveTouchView);
        }
//        lastX = lastY = 0;
    }

    private View getAssistiveTouchView() {
        if (assistiveTouchView == null) {
            assistiveTouchView = LayoutInflater.from(context).inflate(R.layout.leak_canary_assistive_touch, null);
            assistiveTouchView.setOnTouchListener(this);
            assistiveTouchView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (contract != null) {
                        contract.onClick();
                    }
                }
            });
        }
        return assistiveTouchView;
    }

    private void updateLocation(int rawX, int rawY) {
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) getAssistiveTouchView().getLayoutParams();

        //window Y-Axis initial offset
        p.x = rawX;
        p.y = rawY - statusBarHeight;

        /**
         * adjust window to the center of the finger touch, otherwise the finger touch point match
         * the left-top of the window
         */
        p.x -= assistiveTouchView.getWidth() / 2;
        p.y -= assistiveTouchView.getHeight() / 2;
        p.alpha = 1.0f;

        wm.updateViewLayout(getAssistiveTouchView(), p);
        lastX = p.x;
        lastY = p.y;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
//        gestureDetector.onTouchEvent(event);

        final int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                updateLocation((int) event.getRawX(), (int) event.getRawY());
                if (!moveFlag) {
                    moveFlag = true;
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                WindowManager.LayoutParams p = (WindowManager.LayoutParams) getAssistiveTouchView().getLayoutParams();
                p.alpha = MOVING_ALPHA;
                wm.updateViewLayout(getAssistiveTouchView(), p);
                if (moveFlag) {
                    moveFlag = false;
                    return true;
                }
                break;
            }
            default:
        }
        return false;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {

        return true;
    }

    public void updateNumDot(int leakNum) {
        final View dotArea = getAssistiveTouchView().findViewById(R.id.__leak_canary_dot_layout);
        final TextView numTxt = (TextView) getAssistiveTouchView().findViewById(R.id.__leak_canary_leak_num_txt);

        dotArea.setVisibility(leakNum <= 0 ? View.GONE : View.VISIBLE);
        numTxt.setText("" + leakNum);
    }

    public interface AssistiveTouchContract {
        void onClick();
    }

}
