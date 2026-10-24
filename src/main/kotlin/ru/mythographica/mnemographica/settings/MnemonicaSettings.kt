package ru.mythographica.mnemographica.settings

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/**
 * Project-level values for the keys of `InitializeResult.configuration`, plus the host-only `nodePath`.
 * Only values the user changed are stored (JSON-encoded); everything else falls back to the schema default.
 */
@Service(Service.Level.PROJECT)
@State(name = "MnemonicaSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class MnemonicaSettings : PersistentStateComponent<MnemonicaSettings.State> {
    class State {
        var nodePath: String = ""
        var values: MutableMap<String, String> = mutableMapOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var nodePath: String
        get() = state.nodePath
        set(value) {
            state.nodePath = value
        }

    fun storedValue(key: String): JsonElement? = state.values[key]?.let {
        runCatching { JsonParser.parseString(it) }.getOrNull()
    }

    fun setValue(key: String, value: JsonElement?, default: JsonElement?) {
        if (value == null || value == default) state.values.remove(key) else state.values[key] = value.toString()
    }

    /** Flat `mnemographica.<key>` -> value for every schema key (stored value or schema default) plus stored extras. */
    fun snapshot(schema: ConfigSchema?): Map<String, JsonElement> {
        val result = linkedMapOf<String, JsonElement>()
        schema?.properties?.forEach { (key, prop) -> prop.default?.let { result[key] = it } }
        state.values.keys.forEach { key -> storedValue(key)?.let { result[key] = it } }
        return result
    }

    companion object {
        fun getInstance(project: Project): MnemonicaSettings = project.getService(MnemonicaSettings::class.java)
    }
}

/** One `contributes.configuration.properties` entry. */
data class ConfigProperty(
    val key: String,
    val type: String,
    val default: JsonElement?,
    val description: String,
    val enumValues: List<String>,
)

/** Parsed `InitializeResult.configuration`: accepts `{ title, properties }` or the bare `properties` map. */
class ConfigSchema(val title: String?, val properties: Map<String, ConfigProperty>) {
    companion object {
        fun parse(json: JsonObject?): ConfigSchema? {
            if (json == null) return null
            val props = json.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject ?: json
            val title = json.get("title")?.takeIf { it.isJsonPrimitive }?.asString
            val parsed = linkedMapOf<String, ConfigProperty>()
            for ((key, value) in props.entrySet()) {
                if (!value.isJsonObject) continue
                val obj = value.asJsonObject
                val type = obj.get("type")?.let { if (it.isJsonArray) it.asJsonArray.firstOrNull()?.asString else it.asString }
                parsed[key] = ConfigProperty(
                    key = key,
                    type = type ?: "string",
                    default = obj.get("default"),
                    description = obj.get("markdownDescription")?.asString ?: obj.get("description")?.asString ?: "",
                    enumValues = obj.get("enum")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList(),
                )
            }
            return ConfigSchema(title, parsed)
        }
    }
}
