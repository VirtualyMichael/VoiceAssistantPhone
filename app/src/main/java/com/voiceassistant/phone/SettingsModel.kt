package com.voiceassistant.phone

import org.json.JSONArray
import org.json.JSONObject

data class SettingChoice(val value: String, val label: String)

/** One setting as the PC describes it. [value] is a Boolean, a Double, or a String; read-only settings have none and show [summary] instead. */
data class SettingItem(
    val key: String,
    val label: String,
    val help: String,
    val kind: String, // toggle | choice | number | text | readonly
    val value: Any? = null,
    val default: Any? = null,
    val choices: List<SettingChoice> = emptyList(),
    val allowOther: Boolean = false,
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 1.0,
    val integer: Boolean = false,
    val unit: String = "",
    val multiline: Boolean = false,
    val maxLength: Int = 200,
    val summary: String = "",
    val items: List<String> = emptyList(),
    val restartNeeded: Boolean = false, // saving takes effect only when the assistant restarts
    val sensitive: Boolean = false,
    val locked: Boolean = false,
    val essential: Boolean = false,
    val sectionTitle: String = "",
) {
    /** The value as a short line for the row. */
    fun valueText(): String = when (kind) {
        "toggle" -> if (value == true) "On" else "Off"
        "choice" -> choices.firstOrNull { it.value == value }?.label ?: (value as? String).orEmpty().ifBlank { "Not set" }
        "number" -> formatNumber(value as? Double ?: 0.0)
        "text" -> (value as? String).orEmpty().let { if (it.isBlank()) "Not set" else it.replace('\n', ' ').take(60) }
        else -> summary
    }

    fun formatNumber(v: Double): String {
        val body = if (integer || (step >= 1.0 && v % 1.0 == 0.0)) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
        return if (unit.isBlank()) body else if (unit == "$") "$$body" else "$body $unit"
    }

    val isDefault: Boolean get() = default == null || value == default
}

data class SettingSection(val id: String, val title: String, val blurb: String, val items: List<SettingItem>)

data class SettingsSnapshot(val allowSafety: Boolean, val restartPending: Boolean, val sections: List<SettingSection>) {
    val all: List<SettingItem> get() = sections.flatMap { it.items }

    fun find(key: String): SettingItem? = all.firstOrNull { it.key == key }

    fun essentials(): List<SettingItem> = all.filter { it.essential }

    fun section(id: String): SettingSection? = sections.firstOrNull { it.id == id }

    /** Every word of the query must appear in the setting's name, help or section. */
    fun search(query: String): List<SettingItem> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return all.filter { item ->
            val haystack = "${item.label} ${item.help} ${item.sectionTitle}".lowercase()
            words.all { it in haystack }
        }
    }

    fun withValue(key: String, value: Any?): SettingsSnapshot = copy(
        sections = sections.map { s -> s.copy(items = s.items.map { if (it.key == key) it.copy(value = value) else it }) },
    )

    fun withRestartPending(on: Boolean) = copy(restartPending = restartPending || on)

    companion object {
        fun parse(json: JSONObject): SettingsSnapshot {
            val sections = json.optJSONArray("sections") ?: JSONArray()
            return SettingsSnapshot(
                allowSafety = json.optBoolean("allow_safety"),
                restartPending = json.optBoolean("restart_pending"),
                sections = (0 until sections.length()).map { i ->
                    val s = sections.getJSONObject(i)
                    val title = s.optString("title")
                    val items = s.optJSONArray("items") ?: JSONArray()
                    SettingSection(s.optString("id"), title, s.optString("blurb"), (0 until items.length()).map { parseItem(items.getJSONObject(it), title) })
                },
            )
        }

        private fun parseItem(o: JSONObject, sectionTitle: String): SettingItem {
            val kind = o.optString("kind")
            fun scalar(name: String): Any? = if (!o.has(name) || o.isNull(name)) null else when (kind) {
                "toggle" -> o.optBoolean(name)
                "number" -> o.optDouble(name)
                else -> o.optString(name)
            }
            val choices = o.optJSONArray("choices")?.let { a ->
                (0 until a.length()).map { SettingChoice(a.getJSONObject(it).optString("value"), a.getJSONObject(it).optString("label")) }
            }.orEmpty()
            val preview = o.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return SettingItem(
                key = o.optString("key"), label = o.optString("label"), help = o.optString("help"), kind = kind,
                value = scalar("value"), default = scalar("default"), choices = choices, allowOther = o.optBoolean("allow_other"),
                min = o.optDouble("min", 0.0), max = o.optDouble("max", 1.0), step = o.optDouble("step", 1.0), integer = o.optBoolean("integer"),
                unit = o.optString("unit"), multiline = o.optBoolean("multiline"), maxLength = o.optInt("maxlen", 200),
                summary = o.optString("summary"), items = preview, restartNeeded = o.optString("apply") == "restart",
                sensitive = o.optBoolean("sensitive"), locked = o.optBoolean("locked"), essential = o.optBoolean("essential"),
                sectionTitle = sectionTitle,
            )
        }
    }
}
