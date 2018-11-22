package com.squareup.leakcanary.tests;

import android.os.Bundle;
import android.support.test.InstrumentationRegistry;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.squareup.leakcanary.support.fragment.R;

public class ViewLeakingFragment extends Fragment {

  public static void addToBackstack(final TestActivity activity) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
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
