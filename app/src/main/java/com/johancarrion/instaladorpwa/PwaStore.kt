package com.johancarrion.instaladorpwa

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedPwa(val id: String, val name: String, val url: String, val color: Int)

/** Guarda la lista de PWAs instaladas (SharedPreferences) y sus íconos (archivos PNG). */
class PwaStore(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("pwas", Context.MODE_PRIVATE)

    fun all(): List<SavedPwa> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SavedPwa(o.getString("id"), o.getString("name"), o.getString("url"), o.getInt("color"))
        }
    }

    fun save(p: SavedPwa) {
        val list = all().filter { it.id != p.id } + p
        write(list)
    }

    fun remove(id: String) {
        write(all().filter { it.id != id })
        iconFile(id).delete()
    }

    fun iconFile(id: String): File = File(File(ctx.filesDir, "icons"), "$id.png")

    private fun write(list: List<SavedPwa>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("color", it.color))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "list"
        fun idFor(url: String) = "pwa_" + Integer.toHexString(url.hashCode())
    }
}
