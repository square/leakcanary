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
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import com.squareup.leakcanary.LeakTrace;
import com.squareup.leakcanary.LeakTraceElement;
import com.squareup.leakcanary.R;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.squareup.leakcanary.LeakTraceElement.Holder.ARRAY;
import static com.squareup.leakcanary.LeakTraceElement.Holder.THREAD;
import static com.squareup.leakcanary.LeakTraceElement.Type.STATIC_FIELD;

final class DisplayLeakAdapter extends BaseAdapter {

  private static final int TOP_ROW = 0;
  private static final int NORMAL_ROW = 1;

  private boolean[] opened = new boolean[0];

  private List<LeakTraceElement> elements = Collections.emptyList();
  private String referenceKey;
  private String referenceName = "";

  @Override public View getView(int position, View convertView, ViewGroup parent) {
    Context context = parent.getContext();
    if (getItemViewType(position) == TOP_ROW) {
      if (convertView == null) {
        convertView =
            LayoutInflater.from(context).inflate(R.layout.__leak_canary_ref_top_row, parent, false);
      }
      TextView textView = findById(convertView, R.id.__leak_canary_row_text);
      textView.setText(context.getPackageName());
    } else {
      if (convertView == null) {
        convertView =
            LayoutInflater.from(context).inflate(R.layout.__leak_canary_ref_row, parent, false);
      }
      TextView textView = findById(convertView, R.id.__leak_canary_row_text);

      boolean isRoot = position == 1;
      boolean isLeakingInstance = position == getCount() - 1;
      LeakTraceElement element = getItem(position);
      String htmlString = elementToHtmlString(element, isRoot, opened[position]);
      if (isLeakingInstance && !referenceName.equals("") && opened[position]) {
        htmlString += " <font color='#919191'>" + referenceName + "</font>";
      }
      textView.setText(Html.fromHtml(htmlString));

      DisplayLeakConnectorView connector = findById(convertView, R.id.__leak_canary_row_connector);
      if (isRoot) {
        connector.setType(DisplayLeakConnectorView.Type.START);
      } else {
        if (isLeakingInstance) {
          connector.setType(DisplayLeakConnectorView.Type.END);
        } else {
          connector.setType(DisplayLeakConnectorView.Type.NODE);
        }
      }
      MoreDetailsView moreDetailsView = findById(convertView, R.id.__leak_canary_row_more);
      moreDetailsView.setOpened(opened[position]);
    }

    return convertView;
  }

  private String elementToHtmlString(LeakTraceElement element, boolean root, boolean opened) {
    String htmlString = "";

    if (element.referenceName == null) {
      htmlString += "leaks ";
    } else if (!root) {
      htmlString += "references ";
    }

    if (element.type == STATIC_FIELD) {
      htmlString += "<font color='#c48a47'>static</font> ";
    }

    if (element.holder == ARRAY || element.holder == THREAD) {
      htmlString += "<font color='#f3cf83'>" + element.holder.name().toLowerCase() + "</font> ";
    }

    int separator = element.className.lastIndexOf('.');
    String qualifier;
    String simpleName;
    if (separator == -1) {
      qualifier = "";
      simpleName = element.className;
    } else {
      qualifier = element.className.substring(0, separator + 1);
      simpleName = element.className.substring(separator + 1);
    }

    if (opened) {
      htmlString += "<font color='#919191'>" + qualifier + "</font>";
    }

    String styledClassName = "<font color='#ffffff'>" + simpleName + "</font>";

    htmlString += styledClassName;

    if (element.referenceName != null) {
      htmlString += ".<font color='#998bb5'>" + element.referenceName.replaceAll("<", "&lt;")
          .replaceAll(">", "&gt;") + "</font>";
    } else {
      htmlString += " <font color='#f3cf83'>instance</font>";
    }

    if (opened && element.extra != null) {
      htmlString += " <font color='#919191'>" + element.extra + "</font>";
    }
    return htmlString;
  }

  public void update(LeakTrace leakTrace, String referenceKey, String referenceName) {
    if (referenceKey.equals(this.referenceKey)) {
      // Same data, nothing to change.
      return;
    }
    this.referenceKey = referenceKey;
    this.referenceName = referenceName;
    this.elements = new ArrayList<>(leakTrace.elements);
    opened = new boolean[1 + elements.size()];
    notifyDataSetChanged();
  }

  public void toggleRow(int position) {
    opened[position] = !opened[position];
    notifyDataSetChanged();
  }

  @Override public int getCount() {
    return 1 + elements.size();
  }

  @Override public LeakTraceElement getItem(int position) {
    if (getItemViewType(position) == TOP_ROW) {
      return null;
    }
    return elements.get(position - 1);
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

  @SuppressWarnings("unchecked") private static <T extends View> T findById(View view, int id) {
    return (T) view.findViewById(id);
  }
}
