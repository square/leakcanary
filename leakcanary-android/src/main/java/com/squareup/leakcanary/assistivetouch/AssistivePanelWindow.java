package com.squareup.leakcanary.assistivetouch;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ToggleButton;

import com.squareup.leakcanary.R;
import com.squareup.leakcanary.internal.DisplayLeakActivity;

public class AssistivePanelWindow {
    private final Context context;
    private final WindowManager wm;
    private View assistivePanelView;
    private ToggleButton notificationSwitch;
    private Button displayButton, dismissButton;
    private final AssistivePanelContract contract;

    public AssistivePanelWindow(Context context, AssistivePanelContract contract) {
        this.context = context;
        this.contract = contract;
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show(boolean notificationStatus) {
        final Point screenSize = ScreenUtil.getScreenSize(context);

        int curFlags = 0;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                screenSize.x - 200,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0,
                0,
                WindowManager.LayoutParams.TYPE_TOAST, computeFlags(curFlags),
//                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
//                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
//                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,//lock the location
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;

        p.windowAnimations = R.style.leak_canary_assistive_panel_anim;

        wm.addView(getAssistivePanelView(), p);

        notificationSwitch.setChecked(notificationStatus);
    }

    public void dismiss() {
        if (assistivePanelView != null && assistivePanelView.getWindowToken() != null) {
            wm.removeView(assistivePanelView);
            if (contract != null) {
                contract.onDismiss();
            }
        }
    }

    private int computeFlags(int curFlags) {
        curFlags &= ~(
                WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH);
        return curFlags;
    }

    private View getAssistivePanelView() {
        if (assistivePanelView == null) {
            AssistivePanelContainer container = new AssistivePanelContainer(context);
            final View content = LayoutInflater.from(context).inflate(R.layout.leak_canary_assistive_panel, null);
            container.addView(content);
            assistivePanelView = container;
            notificationSwitch = (ToggleButton) content.findViewById(R.id.__leak_canary_notify_switch);
            notificationSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (contract != null) {
                        contract.onNotificationStatusChange(isChecked);
                    }
                }
            });
            displayButton = (Button) content.findViewById(R.id.__leak_canary_display_button);
            displayButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(context, DisplayLeakActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    context.startActivity(intent);
                    dismiss();
                }
            });
            dismissButton = (Button) content.findViewById(R.id.__leak_canary_dismiss_button);
            dismissButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    contract.stopButtonOnClick();
                }
            });
        }
        return assistivePanelView;
    }

    private class AssistivePanelContainer extends FrameLayout {

        public AssistivePanelContainer(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            /**
             * taken from {@link android.widget.PopupWindow}
             */
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (getKeyDispatcherState() == null) {
                    return super.dispatchKeyEvent(event);
                }

                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    KeyEvent.DispatcherState state = getKeyDispatcherState();
                    if (state != null) {
                        state.startTracking(event, this);
                    }
                    return true;
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    KeyEvent.DispatcherState state = getKeyDispatcherState();
                    if (state != null && state.isTracking(event) && !event.isCanceled()) {
                        dismiss();
                        return true;
                    }
                }
                return super.dispatchKeyEvent(event);
            } else {
                return super.dispatchKeyEvent(event);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            /**
             * taken from {@link android.widget.PopupWindow}
             */
            final int x = (int) event.getX();
            final int y = (int) event.getY();

            if ((event.getAction() == MotionEvent.ACTION_DOWN)
                    && ((x < 0) || (x >= getWidth()) || (y < 0) || (y >= getHeight()))) {
                dismiss();
                return true;
            } else if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            } else {
                return super.onTouchEvent(event);
            }
        }
    }

    public interface AssistivePanelContract {
        void onDismiss();

        void onNotificationStatusChange(boolean status);

        void stopButtonOnClick();
    }
}
