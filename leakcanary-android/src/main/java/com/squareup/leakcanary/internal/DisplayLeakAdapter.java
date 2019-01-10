/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary.internal;

import android.content.Context;
import android.content.res.Resources;
import android.support.annotation.ColorRes;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import com.squareup.leakcanary.Exclusion;
import com.squareup.leakcanary.LeakTrace;
import com.squareup.leakcanary.LeakTraceElement;
import com.squareup.leakcanary.R;
import com.squareup.leakcanary.Reachability;

import static com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD;

final class DisplayLeakAdapter extends BaseAdapter {

  private static final int TOP_ROW = 0;
  private static final int NORMAL_ROW = 1;

  private boolean[] opened = new boolean[0];

  private LeakTrace leakTrace = null;
  private String referenceKey;
  private String referenceName = "";

  private final String classNameColorHexString;
  private final String leakColorHexString;
  private final String referenceColorHexString;
  private final String extraColorHexString;
  private final String helpColorHexString;

  DisplayLeakAdapter(Resources resources) {
    classNameColorHexString = hexStringColor(resources, R.color.leak_canary_class_name);
    leakColorHexString = hexStringColor(resources, R.color.leak_canary_leak);
    referenceColorHexString = hexStringColor(resources, R.color.leak_canary_reference);
    extraColorHexString = hexStringColor(resources, R.color.leak_canary_extra);
    helpColorHexString = hexStringColor(resources, R.color.leak_canary_help);
  }

  // https://stackoverflow.com/a/6540378/703646
  private static String hexStringColor(Resources resources, @ColorRes int colorResId) {
    return String.format("#%06X", (0xFFFFFF & resources.getColor(colorResId)));
  }

  @Override public View getView(int position, View convertView, ViewGroup parent) {
    Context context = parent.getContext();
    if (getItemViewType(position) == TOP_ROW) {
      if (convertView == null) {
        convertView =
            LayoutInflater.from(context).inflate(R.layout.leak_canary_ref_top_row, parent, false);
      }
      TextView textView = findById(convertView, R.id.leak_canary_row_text);
      textView.setText(context.getPackageName());
    } else {
      if (convertView == null) {
        convertView =
            LayoutInflater.from(context).inflate(R.layout.leak_canary_ref_row, parent, false);
      }

      TextView titleView = findById(convertView, R.id.leak_canary_row_title);
      TextView detailView = findById(convertView, R.id.leak_canary_row_details);
      DisplayLeakConnectorView connector = findById(convertView, R.id.leak_canary_row_connector);
      MoreDetailsView moreDetailsView = findById(convertView, R.id.leak_canary_row_more);

      connector.setType(getConnectorType(position));
      moreDetailsView.setOpened(opened[position]);

      if (opened[position]) {
        detailView.setVisibility(View.VISIBLE);
      } else {
        detailView.setVisibility(View.GONE);
      }

      Resources resources = convertView.getResources();
      if (position == 1) {
        titleView.setText(Html.fromHtml("<font color='"
            + helpColorHexString
            + "'>"
            + "<b>" + resources.getString(R.string.leak_canary_help_title) + "</b>"
            + "</font>"));
        SpannableStringBuilder detailText =
            (SpannableStringBuilder) Html.fromHtml(
                resources.getString(R.string.leak_canary_help_detail));
        SquigglySpan.replaceUnderlineSpans(detailText, resources);
        detailView.setText(detailText);
      } else {
        boolean isLeakingInstance = position == getCount() - 1;
        LeakTraceElement element = getItem(position);

        Reachability reachability = leakTrace.expectedReachability.get(elementIndex(position));
        boolean maybeLeakCause;
        if (isLeakingInstance || reachability == Reachability.UNREACHABLE) {
          maybeLeakCause = false;
        } else {
          Reachability nextReachability =
              leakTrace.expectedReachability.get(elementIndex(position + 1));
          maybeLeakCause = nextReachability != Reachability.REACHABLE;
        }

        Spanned htmlTitle =
            htmlTitle(element, maybeLeakCause, resources);

        titleView.setText(htmlTitle);

        if (opened[position]) {
          Spanned htmlDetail = htmlDetails(isLeakingInstance, element);
          detailView.setText(htmlDetail);
        }
      }
    }

    return convertView;
  }

  private Spanned htmlTitle(LeakTraceElement element, boolean maybeLeakCause, Resources resources) {
    String htmlString = "";

    String simpleName = element.getSimpleClassName();
    simpleName = simpleName.replace("[]", "[ ]");

    String styledClassName =
        "<font color='" + classNameColorHexString + "'>" + simpleName + "</font>";

    if (element.reference != null) {
      String referenceName = element.reference.getDisplayName().replaceAll("<", "&lt;")
          .replaceAll(">", "&gt;");

      if (maybeLeakCause) {
        referenceName =
            "<u><font color='" + leakColorHexString + "'>" + referenceName + "</font></u>";
      } else {
        referenceName =
            "<font color='" + referenceColorHexString + "'>" + referenceName + "</font>";
      }

      if (element.reference.type == STATIC_FIELD) {
        referenceName = "<i>" + referenceName + "</i>";
      }

      String classAndReference = styledClassName + "." + referenceName;

      if (maybeLeakCause) {
        classAndReference = "<b>" + classAndReference + "</b>";
      }

      htmlString += classAndReference;
    } else {
      htmlString += styledClassName;
    }

    Exclusion exclusion = element.exclusion;
    if (exclusion != null) {
      htmlString += " (excluded)";
    }
    SpannableStringBuilder builder = (SpannableStringBuilder) Html.fromHtml(htmlString);
    if (maybeLeakCause) {
      SquigglySpan.replaceUnderlineSpans(builder, resources);
    }

    return builder;
  }

  private Spanned htmlDetails(boolean isLeakingInstance, LeakTraceElement element) {
    String htmlString = "";
    if (element.extra != null) {
      htmlString += " <font color='" + extraColorHexString + "'>" + element.extra + "</font>";
    }

    Exclusion exclusion = element.exclusion;
    if (exclusion != null) {
      htmlString += "<br/><br/>Excluded by rule";
      if (exclusion.name != null) {
        htmlString += " <font color='#ffffff'>" + exclusion.name + "</font>";
      }
      htmlString += " matching <font color='#f3cf83'>" + exclusion.matching + "</font>";
      if (exclusion.reason != null) {
        htmlString += " because <font color='#f3cf83'>" + exclusion.reason + "</font>";
      }
    }
    htmlString += "<br>"
        + "<font color='" + extraColorHexString + "'>"
        + element.toDetailedString().replace("\n", "<br>")
        + "</font>";

    if (isLeakingInstance && !referenceName.equals("")) {
      htmlString += " <font color='" + extraColorHexString + "'>" + referenceName + "</font>";
    }

    return Html.fromHtml(htmlString);
  }

  private DisplayLeakConnectorView.Type getConnectorType(int position) {
    if (position == 1) {
      return DisplayLeakConnectorView.Type.HELP;
    } else if (position == 2) {
      if (leakTrace.expectedReachability.size() == 1) {
        return DisplayLeakConnectorView.Type.START_LAST_REACHABLE;
      }
      Reachability nextReachability =
          leakTrace.expectedReachability.get(elementIndex(position + 1));
      if (nextReachability != Reachability.REACHABLE) {
        return DisplayLeakConnectorView.Type.START_LAST_REACHABLE;
      }
      return DisplayLeakConnectorView.Type.START;
    } else {
      boolean isLeakingInstance = position == getCount() - 1;
      if (isLeakingInstance) {
        Reachability previousReachability =
            leakTrace.expectedReachability.get(elementIndex(position - 1));
        if (previousReachability != Reachability.UNREACHABLE) {
          return DisplayLeakConnectorView.Type.END_FIRST_UNREACHABLE;
        }
        return DisplayLeakConnectorView.Type.END;
      } else {
        Reachability reachability = leakTrace.expectedReachability.get(elementIndex(position));
        switch (reachability) {
          case UNKNOWN:
            return DisplayLeakConnectorView.Type.NODE_UNKNOWN;
          case REACHABLE:
            Reachability nextReachability =
                leakTrace.expectedReachability.get(elementIndex(position + 1));
            if (nextReachability != Reachability.REACHABLE) {
              return DisplayLeakConnectorView.Type.NODE_LAST_REACHABLE;
            } else {
              return DisplayLeakConnectorView.Type.NODE_REACHABLE;
            }
          case UNREACHABLE:
            Reachability previousReachability =
                leakTrace.expectedReachability.get(elementIndex(position - 1));
            if (previousReachability != Reachability.UNREACHABLE) {
              return DisplayLeakConnectorView.Type.NODE_FIRST_UNREACHABLE;
            } else {
              return DisplayLeakConnectorView.Type.NODE_UNREACHABLE;
            }
          default:
            throw new IllegalStateException("Unknown value: " + reachability);
        }
      }
    }
  }

  public void update(LeakTrace leakTrace, String referenceKey, String referenceName) {
    if (referenceKey.equals(this.referenceKey)) {
      // Same data, nothing to change.
      return;
    }
    this.referenceKey = referenceKey;
    this.referenceName = referenceName;
    this.leakTrace = leakTrace;
    opened = new boolean[2 + leakTrace.elements.size()];
    notifyDataSetChanged();
  }

  public void toggleRow(int position) {
    opened[position] = !opened[position];
    notifyDataSetChanged();
  }

  @Override public int getCount() {
    if (leakTrace == null) {
      return 2;
    }
    return 2 + leakTrace.elements.size();
  }

  @Override public LeakTraceElement getItem(int position) {
    if (getItemViewType(position) == TOP_ROW) {
      return null;
    }
    if (position == 1) {
      return null;
    }
    return leakTrace.elements.get(elementIndex(position));
  }

  private int elementIndex(int position) {
    return position - 2;
  }

  @Override public int getViewTypeCount() {
    return 2;
  }

  @Override public int getItemViewType(int position) {
    if (position == 0) {
      return TOP_ROW;
    }
    return NORMAL_ROW;
  }

  @Override public long getItemId(int position) {
    return position;
  }

  @SuppressWarnings({ "unchecked", "TypeParameterUnusedInFormals" })
  private static <T extends View> T findById(View view, int id) {
    return (T) view.findViewById(id);
  }
}
