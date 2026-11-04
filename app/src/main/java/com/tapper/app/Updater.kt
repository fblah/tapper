package com.tapper.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater. Looks at the GitHub releases of [REPO]; every release body carries a `build=N`
 * line (N = CI run number = the APK's versionCode). If the newest N is higher than the installed
 * versionCode, the APK is downloaded and handed to Android's installer.
 * Blocking calls: run them off the main thread.
 */
object Updater {
    const val REPO = "fblah/tapper"

    data class Release(val build: Int, val name: String, val apkUrl: String, val size: Long)

    fun installedBuild(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }

    fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "Tapper-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    /** The newest published build, or null if there are no releases with an APK. */
    fun fetchLatest(): Release? {
        val conn = open("https://api.github.com/repos/$REPO/releases?per_page=20")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub returned HTTP ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            var best: Release? = null
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                val build = Regex("build=(\\d+)").find(r.optString("body"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    if (a.optString("name").endsWith(".apk") && (best == null || build > best.build)) {
                        best = Release(build, r.optString("name").ifEmpty { r.optString("tag_name") },
                            a.getString("browser_download_url"), a.optLong("size"))
                    }
                }
            }
            return best
        } finally {
            conn.disconnect()
        }
    }

    /** Download the APK into the app's cache; [onProgress] gets 0..100 (or -1 if size unknown). */
    fun download(ctx: Context, release: Release, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "tapper-update.apk")
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Download failed: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var lastPct = -2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    fun canInstall(ctx: Context) =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Ask Android to let Tapper install apps (a one-time per-app switch). */
    fun requestInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
