package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.annotation.StringRes

data class ToolItem(
    val name: String,               // e.g. "make_file"
    val displayName: String,        // Human-readable, e.g. "Create file"
    val description: String,        // Short description shown under the name
    val isEnabled: Boolean // Current state from prefs
) {
    companion object {
        private const val WORKSPACE = "Download/gradation"

        fun isToolEnabled(toolName: String, enabledSet: Set<String>): Boolean {
            if (toolName in enabledSet) return true
            return when (toolName) {
                "list_gradation_files" ->
                    "list_grokion_files" in enabledSet || "list_oxproxion_files" in enabledSet
                "read_gradation_file" ->
                    "read_grokion_file" in enabledSet || "read_oxproxion_file" in enabledSet
                "list_grokion_files" -> "list_oxproxion_files" in enabledSet
                "read_grokion_file" -> "read_oxproxion_file" in enabledSet
                else -> false
            }
        }

        fun effectiveEnabledTools(enabledSet: Set<String>): Set<String> {
            val result = enabledSet.toMutableSet()
            if ("list_oxproxion_files" in enabledSet || "list_grokion_files" in enabledSet) {
                result.add("list_gradation_files")
            }
            if ("read_oxproxion_file" in enabledSet || "read_grokion_file" in enabledSet) {
                result.add("read_gradation_file")
            }
            return result
        }

        /**
         * Older installs stored the file tools under the previous app names. The row still
         * shows those as on, and the request still sends them, so turning the row off has
         * to drop every alias or the tool comes back on the next open.
         */
        fun aliasNames(toolName: String): Set<String> = when (toolName) {
            "list_gradation_files" -> setOf(toolName, "list_grokion_files", "list_oxproxion_files")
            "read_gradation_file" -> setOf(toolName, "read_grokion_file", "read_oxproxion_file")
            else -> setOf(toolName)
        }

        fun enabledToolsAfterToggle(stored: Set<String>, toolName: String, enabled: Boolean): Set<String> {
            val next = stored.toMutableSet()
            if (enabled) next.add(toolName) else aliasNames(toolName).forEach { next.remove(it) }
            return next
        }

        /**
         * The folder grant is for tools that open the workspace tree. Create file writes
         * through MediaStore into Download/gradation and does not need that grant; treating
         * every name that contains "file" as one locked the switch until a folder was picked.
         */
        fun needsFolderGrant(toolName: String): Boolean = toolName in FOLDER_GRANT_TOOLS

        private val FOLDER_GRANT_TOOLS = setOf(
            "delete_files",
            "list_gradation_files",
            "read_gradation_file",
            "open_file",
            "edit_file",
            "copy_file",
        )

        /** Names and descriptions come from resources; `%1$s` in a description is the workspace folder. */
        fun getAllToolItems(enabledSet: Set<String>, context: Context): List<ToolItem> {
            fun item(name: String, @StringRes label: Int, @StringRes desc: Int, enabled: Boolean) =
                ToolItem(name, context.getString(label), context.getString(desc, WORKSPACE), enabled)
            return listOf(
                item("make_file", R.string.tool_make_file_name, R.string.tool_make_file_desc, "make_file" in enabledSet),
                item("delete_files", R.string.tool_delete_files_name, R.string.tool_delete_files_desc, "delete_files" in enabledSet),
                item("get_location", R.string.tool_get_location_name, R.string.tool_get_location_desc, "get_location" in enabledSet),
                item("brave_search", R.string.tool_brave_search_name, R.string.tool_brave_search_desc, "brave_search" in enabledSet),
                item("brave_news", R.string.tool_brave_news_name, R.string.tool_brave_news_desc, "brave_news" in enabledSet),
                item("find_nearby_places", R.string.tool_find_nearby_places_name, R.string.tool_find_nearby_places_desc, "find_nearby_places" in enabledSet),
                item("set_timer", R.string.tool_set_timer_name, R.string.tool_set_timer_desc, "set_timer" in enabledSet),
                item("set_alarm", R.string.tool_set_alarm_name, R.string.tool_set_alarm_desc, "set_alarm" in enabledSet),
                item("add_calendar_event", R.string.tool_add_calendar_event_name, R.string.tool_add_calendar_event_desc, "add_calendar_event" in enabledSet),
                item("list_gradation_files", R.string.tool_list_files_name, R.string.tool_list_files_desc, isToolEnabled("list_gradation_files", enabledSet)),
                item("read_gradation_file", R.string.tool_read_file_name, R.string.tool_read_file_desc, isToolEnabled("read_gradation_file", enabledSet)),
                item("create_folder", R.string.tool_create_folder_name, R.string.tool_create_folder_desc, "create_folder" in enabledSet),
                item("open_file", R.string.tool_open_file_name, R.string.tool_open_file_desc, "open_file" in enabledSet),
                item("edit_file", R.string.tool_edit_file_name, R.string.tool_edit_file_desc, "edit_file" in enabledSet),
                item("copy_file", R.string.tool_copy_file_name, R.string.tool_copy_file_desc, "copy_file" in enabledSet),
                item("process_plus_code", R.string.tool_plus_code_name, R.string.tool_plus_code_desc, "process_plus_code" in enabledSet),
                item("start_navigation", R.string.tool_start_navigation_name, R.string.tool_start_navigation_desc, "start_navigation" in enabledSet),
                item("get_current_datetime", R.string.tool_datetime_name, R.string.tool_datetime_desc, "get_current_datetime" in enabledSet),
                item("open_app", R.string.tool_open_app_name, R.string.tool_open_app_desc, "open_app" in enabledSet),
                item("search_list_apps", R.string.tool_search_apps_name, R.string.tool_search_apps_desc, "search_list_apps" in enabledSet),
                item("set_sound_mode", R.string.tool_sound_mode_name, R.string.tool_sound_mode_desc, "set_sound_mode" in enabledSet),
                item("wait", R.string.tool_wait_name, R.string.tool_wait_desc, "wait" in enabledSet)
            )
        }
    }
}
