package com.tapper.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Named snapshots of the current layout (all buttons) plus run settings. */
object Profiles {
    private const val PREFS = "tapper_profiles"
    private const val SETTINGS = "tapper_settings"

    fun names(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys.sorted()

    /** Current layout + settings as JSON. */
    fun snapshot(ctx: Context): JSONObject {
        val settings = JSONObject()
        ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).all.forEach { (k, v) -> settings.put(k, v) }
        val targets = JSONArray()
        TargetStore.load(ctx).forEach {
            targets.put(
                JSONObject().put("x", it.x).put("y", it.y).put("interval", it.intervalMs)
                    .put("repeat", it.repeat).put("swipe", it.swipe)
                    .put("x2", it.x2).put("y2", it.y2).put("swipeMs", it.swipeMs)
            )
        }
        return JSONObject().put("version", 1).put("settings", settings).put("targets", targets)
    }

    /** Replace the current layout + settings with [json]. Throws if it isn't a valid profile. */
    fun apply(ctx: Context, json: JSONObject) {
        val targets = json.getJSONArray("targets")
        val parsed = MutableList(targets.length()) {
            val o = targets.getJSONObject(it)
            Target(
                o.getInt("x"), o.getInt("y"), o.getLong("interval"), o.optInt("repeat", 0),
                o.optBoolean("swipe", false), o.optInt("x2", 0), o.optInt("y2", 0), o.optLong("swipeMs", 300L)
            )
        }
        val edit = ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().clear()
        val settings = json.optJSONObject("settings") ?: JSONObject()
        settings.keys().forEach { k ->
            when (val v = settings.get(k)) {
                is Boolean -> edit.putBoolean(k, v)
                is Int -> edit.putInt(k, v)
                is Long -> edit.putLong(k, v)
                is String -> edit.putString(k, v)
                is Number -> edit.putFloat(k, v.toFloat())
            }
        }
        edit.apply()
        TargetStore.save(ctx, parsed)
        TapService.instance?.reload()
    }

    fun save(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(name, snapshot(ctx).toString()).apply()
    }

    fun load(ctx: Context, name: String) {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return
        apply(ctx, JSONObject(raw))
    }

    fun delete(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply()
    }
}
