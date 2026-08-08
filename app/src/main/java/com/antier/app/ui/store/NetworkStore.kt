package com.antier.app.ui.store

import android.content.Context
import com.antier.app.ui.model.NetworkConfig
import org.json.JSONArray
import org.json.JSONObject

/** 一条持久化的网络配置。 */
data class StoredNetwork(
    val id: String,
    val toml: String
) {
    val config: NetworkConfig
        get() = NetworkConfig.fromToml(toml)
}

/** 网络配置的本地持久化（SharedPreferences，JSON 数组保序存储）。 */
object NetworkStore {
    private const val PREFS = "antier_networks"
    private const val KEY_LIST = "networks"

    fun loadAll(context: Context): List<StoredNetwork> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIST, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val toml = obj.optString("toml") ?: continue
                    add(StoredNetwork(id, toml))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun get(context: Context, id: String): StoredNetwork? =
        loadAll(context).firstOrNull { it.id == id }

    fun save(context: Context, id: String, toml: String) {
        val current = loadAll(context)
        val updated = if (current.any { it.id == id }) {
            current.map { if (it.id == id) StoredNetwork(id, toml) else it }
        } else {
            current + StoredNetwork(id, toml)
        }
        persist(context, updated)
    }

    fun delete(context: Context, id: String) {
        persist(context, loadAll(context).filterNot { it.id == id })
    }

    private fun persist(context: Context, networks: List<StoredNetwork>) {
        val array = JSONArray()
        networks.forEach { network ->
            array.put(
                JSONObject()
                    .put("id", network.id)
                    .put("toml", network.toml)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, array.toString())
            .apply()
    }
}
