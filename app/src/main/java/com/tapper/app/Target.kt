package com.tapper.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A virtual tap button. [x]/[y] are the top-left of its overlay window; [intervalMs] is the tap period. */
data class Target(
    var x: Int, var y: Int,
    var intervalMs: Long = 1000L,
    /** Number of taps before this button stops itself; 0 = unlimited. */
    var repeat: Int = 0,
    /** Swipe buttons drag from (x,y) to (x2,y2) over [swipeMs]; plain buttons just tap. */
    var swipe: Boolean = false,
    var x2: Int = 0, var y2: Int = 0,
    var swipeMs: Long = 300L
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
                Target(o.getInt("x"), o.getInt("y"), o.getLong("interval"), o.optInt("repeat", 0),
                    o.optBoolean("swipe", false), o.optInt("x2", 0), o.optInt("y2", 0), o.optLong("swipeMs", 300L))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, targets: List<Target>) {
        val arr = JSONArray()
        targets.forEach {
            arr.put(JSONObject().put("x", it.x).put("y", it.y).put("interval", it.intervalMs).put("repeat", it.repeat)
                .put("swipe", it.swipe).put("x2", it.x2).put("y2", it.y2).put("swipeMs", it.swipeMs))
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

    /** Sequence mode: buttons fire one after another instead of each on its own timer. */
    fun sequence(ctx: Context) = p(ctx).getBoolean("sequence", false)
    fun setSequence(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("sequence", v).apply()

    /** Sequence mode: how many times to run through the whole list; 0 = forever. */
    fun cycles(ctx: Context) = p(ctx).getInt("cycles", 0)
    fun setCycles(ctx: Context, v: Int) = p(ctx).edit().putInt("cycles", v).apply()

    /** Random variation of each interval, +/- this many percent (0-90). */
    fun jitterPct(ctx: Context) = p(ctx).getInt("jitterPct", 0)
    fun setJitterPct(ctx: Context, v: Int) = p(ctx).edit().putInt("jitterPct", v.coerceIn(0, 90)).apply()

    /** Random offset of each tap position, +/- this many pixels. */
    fun jitterPx(ctx: Context) = p(ctx).getInt("jitterPx", 0)
    fun setJitterPx(ctx: Context, v: Int) = p(ctx).edit().putInt("jitterPx", v.coerceIn(0, 200)).apply()
}
