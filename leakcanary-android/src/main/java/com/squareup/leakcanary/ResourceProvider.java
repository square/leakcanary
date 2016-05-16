package com.squareup.leakcanary;

public class ResourceProvider {
    private static final IResourceProvider defaultResourceProvider = new IResourceProvider() {
        @Override
        public int leak_canary_notification() {
            return R.drawable.leak_canary_notification;
        }

        @Override
        public int leak_canary_action() {
            return R.id.leak_canary_action;
        }

        @Override
        public int leak_canary_display_leak_failure() {
            return R.id.leak_canary_display_leak_failure;
        }

        @Override
        public int leak_canary_display_leak_list() {
            return R.id.leak_canary_display_leak_list;
        }

        @Override
        public int leak_canary_row_connector() {
            return R.id.leak_canary_row_connector;
        }

        @Override
        public int leak_canary_row_more() {
            return R.id.leak_canary_row_more;
        }

        @Override
        public int leak_canary_row_text() {
            return R.id.leak_canary_row_text;
        }

        @Override
        public int leak_canary_row_time() {
            return R.id.leak_canary_row_time;
        }

        @Override
        public int leak_canary_max_stored_leaks() {
            return R.integer.leak_canary_max_stored_leaks;
        }

        @Override
        public int leak_canary_watch_delay_millis() {
            return R.integer.leak_canary_watch_delay_millis;
        }

        @Override
        public int leak_canary_display_leak() {
            return R.layout.leak_canary_display_leak;
        }

        @Override
        public int leak_canary_heap_dump_toast() {
            return R.layout.leak_canary_heap_dump_toast;
        }

        @Override
        public int leak_canary_leak_row() {
            return R.layout.leak_canary_leak_row;
        }

        @Override
        public int leak_canary_ref_row() {
            return R.layout.leak_canary_ref_row;
        }

        @Override
        public int leak_canary_ref_top_row() {
            return R.layout.leak_canary_ref_top_row;
        }

        @Override
        public int leak_canary_analysis_failed() {
            return R.string.leak_canary_analysis_failed;
        }

        @Override
        public int leak_canary_class_has_leaked() {
            return R.string.leak_canary_class_has_leaked;
        }

        @Override
        public int leak_canary_could_not_save_text() {
            return R.string.leak_canary_could_not_save_text;
        }

        @Override
        public int leak_canary_could_not_save_title() {
            return R.string.leak_canary_could_not_save_title;
        }

        @Override
        public int leak_canary_delete() {
            return R.string.leak_canary_delete;
        }

        @Override
        public int leak_canary_delete_all() {
            return R.string.leak_canary_delete_all;
        }

        @Override
        public int leak_canary_excluded_row() {
            return R.string.leak_canary_excluded_row;
        }

        @Override
        public int leak_canary_failure_report() {
            return R.string.leak_canary_failure_report;
        }

        @Override
        public int leak_canary_leak_excluded() {
            return R.string.leak_canary_leak_excluded;
        }

        @Override
        public int leak_canary_leak_list_title() {
            return R.string.leak_canary_leak_list_title;
        }

        @Override
        public int leak_canary_no_leak_text() {
            return R.string.leak_canary_no_leak_text;
        }

        @Override
        public int leak_canary_no_leak_title() {
            return R.string.leak_canary_no_leak_title;
        }

        @Override
        public int leak_canary_notification_message() {
            return R.string.leak_canary_notification_message;
        }

        @Override
        public int leak_canary_permission_not_granted() {
            return R.string.leak_canary_permission_not_granted;
        }

        @Override
        public int leak_canary_permission_notification_text() {
            return R.string.leak_canary_permission_notification_text;
        }

        @Override
        public int leak_canary_permission_notification_title() {
            return R.string.leak_canary_permission_notification_title;
        }

        @Override
        public int leak_canary_share_heap_dump() {
            return R.string.leak_canary_share_heap_dump;
        }

        @Override
        public int leak_canary_share_leak() {
            return R.string.leak_canary_share_leak;
        }

        @Override
        public int leak_canary_share_with() {
            return R.string.leak_canary_share_with;
        }

        @Override
        public int leak_canary_LeakCanary_Base() {
            return R.style.leak_canary_LeakCanary_Base;
        }
    };

    private static IResourceProvider provider;

    private ResourceProvider() {
    }

    public static IResourceProvider provider() {
        return provider == null ? defaultResourceProvider : provider;
    }

    public static void setProvider(IResourceProvider provider) {
        ResourceProvider.provider = provider;
    }
}
