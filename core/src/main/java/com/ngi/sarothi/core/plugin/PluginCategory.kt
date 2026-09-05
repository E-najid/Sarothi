package com.ngi.sarothi.core.plugin

/** The eight functional families the specification asks for. */
enum class PluginCategory(val displayName: String) {
    COMMUNICATION("Communication"),
    PRODUCTIVITY("Productivity"),
    INFORMATION("Information"),
    SHOPPING("Shopping & Money"),
    SYSTEM("System"),
    SMART_HOME("Smart Home"),
    VOICE("Voice"),
    CONNECTOR("Connectors"),
    META("Sarothi Meta");

    companion object {
        fun fromJson(value: String?): PluginCategory? = value?.let { raw ->
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) || it.displayName.equals(raw, true) }
        }
    }
}
