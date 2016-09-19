package com.squareup.leakcanary;

/**
 * Provide resource for custom resources.
 */
public interface IResourceProvider {
    int leak_canary_notification();

    int leak_canary_action();

    int leak_canary_display_leak_failure();

    int leak_canary_display_leak_list();

    int leak_canary_row_connector();

    int leak_canary_row_more();

    int leak_canary_row_text();

    int leak_canary_row_time();

    int leak_canary_max_stored_leaks();

    int leak_canary_watch_delay_millis();

    int leak_canary_display_leak();

    int leak_canary_heap_dump_toast();

    int leak_canary_leak_row();

    int leak_canary_ref_row();

    int leak_canary_ref_top_row();

    int leak_canary_analysis_failed();

    int leak_canary_class_has_leaked();

    int leak_canary_could_not_save_text();

    int leak_canary_could_not_save_title();

    int leak_canary_delete();

    int leak_canary_delete_all();

    int leak_canary_excluded_row();

    int leak_canary_failure_report();

    int leak_canary_leak_excluded();

    int leak_canary_leak_list_title();

    int leak_canary_no_leak_text();

    int leak_canary_no_leak_title();

    int leak_canary_notification_message();

    int leak_canary_permission_not_granted();

    int leak_canary_permission_notification_text();

    int leak_canary_permission_notification_title();

    int leak_canary_share_heap_dump();

    int leak_canary_share_leak();

    int leak_canary_share_with();

    int leak_canary_LeakCanary_Base();
}
