package com.squareup.leakcanary.tests;

import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.squareup.leakcanary.support.fragment.R;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

public class ViewLeakingFragment extends Fragment {

  public static void addToBackstack(final TestActivity activity) {
    getInstrumentation().runOnMainSync(new Runnable() {
      @Override public void run() {
        activity.getSupportFragmentManager()
            .beginTransaction()
            .addToBackStack(null)
            .replace(R.id.fragments, new ViewLeakingFragment())
            .commit();
      }
    });
  }

  private View leakingView;

  @Override public View onCreateView(LayoutInflater inflater, ViewGroup container,
      Bundle savedInstanceState) {
    return new View(container.getContext());
  }

  @Override
  public void onViewCreated(View view, Bundle savedInstanceState) {
    // Leak: this fragment will stay in memory after being replaced, leakingView should be cleared
    // onDestroyView()
    leakingView = view;
  }
}
