package leakcanary;

import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import java.util.List;

/**
 * Implementation of Window.Callback that updates the signature of
 * {@link #onMenuOpened(int, Menu)} to change the menu param from
 * non null to nullable to avoid runtime null check crashes.
 * Issue: https://issuetracker.google.com/issues/188568911
 */
class FixedWindowCallback implements Window.Callback {

  private final Window.Callback delegate;

  FixedWindowCallback(@NonNull Window.Callback delegate) {
    this.delegate = delegate;
  }

  @Override public boolean dispatchKeyEvent(KeyEvent event) {
    return delegate.dispatchKeyEvent(event);
  }

  @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) {
    return delegate.dispatchKeyShortcutEvent(event);
  }

  @Override public boolean dispatchTouchEvent(MotionEvent event) {
    return delegate.dispatchTouchEvent(event);
  }

  @Override public boolean dispatchTrackballEvent(MotionEvent event) {
    return delegate.dispatchTrackballEvent(event);
  }

  @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
    return delegate.dispatchGenericMotionEvent(event);
  }

  @Override public boolean dispatchPopulateAccessibilityEvent(
      AccessibilityEvent event) {
    return delegate.dispatchPopulateAccessibilityEvent(event);
  }

  @Nullable @Override public View onCreatePanelView(int featureId) {
    return delegate.onCreatePanelView(featureId);
  }

  @Override public boolean onCreatePanelMenu(int featureId, @NonNull Menu menu) {
    return delegate.onCreatePanelMenu(featureId, menu);
  }

  @Override public boolean onPreparePanel(int featureId, @Nullable View view,
      @NonNull Menu menu) {
    return delegate.onPreparePanel(featureId, view, menu);
  }

  @Override public boolean onMenuOpened(int featureId, @Nullable Menu menu) {
    return delegate.onMenuOpened(featureId, menu);
  }

  @Override public boolean onMenuItemSelected(int featureId,
      @NonNull MenuItem item) {
    return delegate.onMenuItemSelected(featureId, item);
  }

  @Override public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
    delegate.onWindowAttributesChanged(attrs);
  }

  @Override public void onContentChanged() {
    delegate.onContentChanged();
  }

  @Override public void onWindowFocusChanged(boolean hasFocus) {
    delegate.onWindowFocusChanged(hasFocus);
  }

  @Override public void onAttachedToWindow() {
    delegate.onAttachedToWindow();
  }

  @Override public void onDetachedFromWindow() {
    delegate.onDetachedFromWindow();
  }

  @Override public void onPanelClosed(int featureId, @NonNull Menu menu) {
    delegate.onPanelClosed(featureId, menu);
  }

  @Override public boolean onSearchRequested() {
    return delegate.onSearchRequested();
  }

  @RequiresApi(23)
  @Override public boolean onSearchRequested(SearchEvent searchEvent) {
    return delegate.onSearchRequested(searchEvent);
  }

  @Nullable @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
    return delegate.onWindowStartingActionMode(callback);
  }

  @RequiresApi(23) @Nullable @Override
  public ActionMode onWindowStartingActionMode(ActionMode.Callback callback,
      int type) {
    return delegate.onWindowStartingActionMode(callback, type);
  }

  @Override public void onActionModeStarted(ActionMode mode) {
    delegate.onActionModeStarted(mode);
  }

  @Override public void onActionModeFinished(ActionMode mode) {
    delegate.onActionModeFinished(mode);
  }

  @RequiresApi(24)
  @Override public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data,
      @Nullable Menu menu, int deviceId) {
    delegate.onProvideKeyboardShortcuts(data, menu, deviceId);
  }

  @RequiresApi(26)
  @Override public void onPointerCaptureChanged(boolean hasCapture) {
    delegate.onPointerCaptureChanged(hasCapture);
  }
}
