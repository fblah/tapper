package com.tapper.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A virtual tap button. [x]/[y] are the top-left of its overlay window; [intervalMs] is the tap period. */
data class Target(
    var x: Int, var y: Int,
    var intervalMs: Long = 1000L,
    /** Number of taps before this button stops itself; 0 = unlimited. */
    var repeat: Int = 0
)

object TargetStore {
    private const val PREFS = "tapper"
    private const val KEY = "targets"

    fun load(ctx: Context): MutableList<Target> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) {
                val o = arr.getJSONObject(it)
                Target(o.getInt("x"), o.getInt("y"), o.getLong("interval"), o.optInt("repeat", 0))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, targets: List<Target>) {
        val arr = JSONArray()
        targets.forEach {
            arr.put(JSONObject().put("x", it.x).put("y", it.y).put("interval", it.intervalMs).put("repeat", it.repeat))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

/** Global run settings, edited on the main screen. */
object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("tapper_settings", Context.MODE_PRIVATE)

    /** Stop everything after this many minutes; 0 = no timer. */
    fun timerMinutes(ctx: Context) = p(ctx).getInt("timerMinutes", 0)
    fun setTimerMinutes(ctx: Context, v: Int) = p(ctx).edit().putInt("timerMinutes", v).apply()
}
