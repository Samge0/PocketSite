package com.samge.pocketsite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SiteInfo(
    val name: String,
    val port: Int,
    val sizeBytes: Long,
    val fileCount: Int,
    val hasIndex: Boolean,
    val running: Boolean,
)

/**
 * Multi-site repository. Every site is a directory under rootDir plus a port assigned
 * in prefs (persisted, collision-checked). No bundled default site — all sites come
 * from ZIP import or URL fetch.
 */
object SitesRepository {
    private const val PREFS = "pocketsite_prefs"
    private const val KEY_ROOT = "root_path"
    private const val KEY_PORT_PREFIX = "port_" // port_<siteName>
    private const val KEY_AUTOSTART = "autostart_"
    const val DEFAULT_PORT = 8808
    const val PORT_MIN = 8000
    const val PORT_MAX = 9999

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun defaultRoot(ctx: Context): File = File(ctx.filesDir, "sites")

    fun rootDir(ctx: Context): File {
        val saved = prefs(ctx).getString(KEY_ROOT, null)
        val f = if (saved != null) File(saved) else defaultRoot(ctx)
        if (!f.exists()) f.mkdirs()
        return f
    }

    fun setRoot(ctx: Context, f: File) {
        prefs(ctx).edit().putString(KEY_ROOT, f.absolutePath).apply()
    }

    // ---------- ports ----------

    fun sitePort(ctx: Context, name: String): Int {
        val saved = prefs(ctx).getInt(KEY_PORT_PREFIX + name, -1)
        if (saved in PORT_MIN..PORT_MAX) return saved
        val p = allocatePort(ctx)
        prefs(ctx).edit().putInt(KEY_PORT_PREFIX + name, p).apply()
        return p
    }

    fun setSitePort(ctx: Context, name: String, port: Int) {
        prefs(ctx).edit().putInt(KEY_PORT_PREFIX + name, port).apply()
    }

    /** Random non-conflicting port: not used by any other site, not bound on this device. */
    private fun allocatePort(ctx: Context): Int {
        val used = usedPorts(ctx)
        var attempts = 0
        while (attempts < 200) {
            val candidate = PORT_MIN + (0..(PORT_MAX - PORT_MIN)).random()
            if (candidate in used) {
                attempts++
                continue
            }
            if (!portFreeOnDevice(candidate)) {
                attempts++
                continue
            }
            return candidate
        }
        // fallback: scan sequentially
        for (p in PORT_MIN..PORT_MAX) {
            if (p !in used && portFreeOnDevice(p)) return p
        }
        throw IllegalStateException("no free port available")
    }

    fun usedPorts(ctx: Context): Set<Int> {
        val all = prefs(ctx).all
        return all.keys.filter { it.startsWith(KEY_PORT_PREFIX) }
            .mapNotNull { all[it] as? Int }
            .toSet()
    }

    fun portFreeOnDevice(port: Int): Boolean =
        try {
            java.net.ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }

    // ---------- active site (server boot target handled per-site) ----------

    // ---------- per-site autostart ----------

    fun autostart(ctx: Context, name: String): Boolean =
        prefs(ctx).getBoolean(KEY_AUTOSTART + name, false)


    fun setAutostart(ctx: Context, name: String, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTOSTART + name, v).apply()
    }

    // ---------- last-used picker directories (import zip / export config) ----------

    fun lastZipDir(ctx: Context): String? = prefs(ctx).getString("last_zip_dir", null)

    fun setLastZipDir(ctx: Context, uri: String) {
        prefs(ctx).edit().putString("last_zip_dir", uri).apply()
    }

    fun lastConfigDir(ctx: Context): String? = prefs(ctx).getString("last_config_dir", null)

    fun setLastConfigDir(ctx: Context, uri: String) {
        prefs(ctx).edit().putString("last_config_dir", uri).apply()
    }

    // ---------- import source memory (single kind+value per site; last one wins) ----------

    fun setImportSource(ctx: Context, name: String, kind: String, value: String) {
        prefs(ctx).edit()
            .putString("src_kind_" + name, kind)   // "uri" | "url"
            .putString("src_value_" + name, value)
            .apply()
    }

    /** Pair(kind, value) or null if never recorded. */
    fun importSource(ctx: Context, name: String): Pair<String, String>? {
        val kind = prefs(ctx).getString("src_kind_" + name, null) ?: return null
        val value = prefs(ctx).getString("src_value_" + name, null) ?: return null
        return kind to value
    }

    fun clearImportSource(ctx: Context, name: String) {
        prefs(ctx).edit()
            .remove("src_kind_" + name)
            .remove("src_value_" + name)
            .apply()
    }

    fun autostartSites(ctx: Context): List<String> {
        val all = prefs(ctx).all
        return all.keys.filter { it.startsWith(KEY_AUTOSTART) && all[it] == true }
            .map { it.removePrefix(KEY_AUTOSTART) }
            .filter { File(rootDir(ctx), it).isDirectory }
    }

    // ---------- listing ----------

    fun listSites(ctx: Context): List<SiteInfo> {
        val root = rootDir(ctx)
        val out = mutableListOf<SiteInfo>()
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return out
        for (d in dirs) {
            var size = 0L
            var count = 0
            var hasIndex = false
            d.walkTopDown().forEach { f ->
                if (f.isFile) {
                    size += f.length()
                    count++
                    if (f.name == "index.html") hasIndex = true
                }
            }
            out.add(
                SiteInfo(
                    name = d.name,
                    port = sitePort(ctx, d.name),
                    sizeBytes = size,
                    fileCount = count,
                    hasIndex = hasIndex,
                    running = false, // filled by caller from service state
                ),
            )
        }
        return out.sortedBy { it.name }
    }

    // ---------- delete / rename ----------

    fun deleteSite(ctx: Context, name: String): Boolean {
        val d = File(rootDir(ctx), name)
        val ok = !d.exists() || d.deleteRecursively()
        if (ok) {
            val p = prefs(ctx)
            p.edit()
                .remove(KEY_PORT_PREFIX + name)
                .remove(KEY_AUTOSTART + name)
                .remove("src_kind_" + name)
                .remove("src_value_" + name)
                .apply()
        }
        return ok
    }

    fun deleteSiteAsync(ctx: Context, name: String, onDone: (Boolean, String) -> Unit) {
        io.launch {
            val r =
                withContext(Dispatchers.IO) {
                    val ok = deleteSite(ctx, name)
                    if (ok) true to "已删除 $name" else false to "删除失败"
                }
            withContext(Dispatchers.Main) { onDone(r.first, r.second) }
        }
    }

    /** Replace site content with a new zip, keeping its port/autostart prefs. */
    fun updateSiteAsync(
        ctx: Context,
        zipFile: File,
        existingName: String,
        onDone: (Boolean, String) -> Unit,
    ) {
        io.launch {
            val r = withContext(Dispatchers.IO) { importZip(ctx, zipFile, existingName, keepPrefs = true) }
            withContext(Dispatchers.Main) { onDone(r.first, r.second) }
        }
    }

    // ---------- import ----------

    fun importZipAsync(
        ctx: Context,
        zipFile: File,
        name: String,
        onDone: (Boolean, String) -> Unit,
    ) {
        io.launch {
            val r = withContext(Dispatchers.IO) { importZip(ctx, zipFile, name) }
            withContext(Dispatchers.Main) { onDone(r.first, r.second) }
        }
    }

    /** Import from a picked content Uri; remembers the Uri for next-time default. */
    fun importZipFromUriAsync(
        ctx: Context,
        uri: Uri,
        name: String,
        onDone: (Boolean, String) -> Unit,
    ) {
        io.launch {
            // persist read permission so the same Uri can be re-used later
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) {
                // OpenDocument uris are persistable; others may throw — non-fatal
            }
            val tmp = File(ctx.cacheDir, "import_$name.zip")
            val okCopy = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            if (!okCopy) {
                withContext(Dispatchers.Main) { onDone(false, "无法读取所选文件") }
                return@launch
            }
            val r = withContext(Dispatchers.IO) { importZip(ctx, tmp, name) }
            tmp.delete()
            if (r.first) setImportSource(ctx, name, "uri", uri.toString())
            withContext(Dispatchers.Main) { onDone(r.first, r.second) }
        }
    }

    private fun importZip(
        ctx: Context,
        zipFile: File,
        rawName: String,
        keepPrefs: Boolean = false,
    ): Pair<Boolean, String> {
        if (!zipFile.isFile || zipFile.length() == 0L) return false to "ZIP 不存在或为空"
        val name = sanitizeName(rawName)
        if (name.isEmpty()) return false to "站点名无效"
        val wasRunning = SiteServerService.isRunning(name)
        if (wasRunning) {
            // stop first so we're not deleting files a live server is serving
            SiteServerService.stopSiteStatic(ctx, name)
            Thread.sleep(300) // give the accept thread a moment to release fds
        }
        val root = rootDir(ctx)
        val dest = File(root, name)
        val staging = File(root, ".$name.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            var total = 0L
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                val prefix = commonPrefixFirst(entry)
                while (entry != null) {
                    if (!entry.isDirectory && !entry.name.startsWith("__MACOSX")) {
                        var rel = entry.name
                        if (prefix.isNotEmpty() && rel.startsWith(prefix)) rel = rel.removePrefix(prefix)
                        if (rel.isNotEmpty()) {
                            val outFile = File(staging, rel)
                            if (!outFile.canonicalPath.startsWith(staging.canonicalPath)) {
                                return false to "ZIP 路径异常（疑似路径穿越）"
                            }
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fo -> zis.copyTo(fo) }
                            total += outFile.length()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (total == 0L) {
                staging.deleteRecursively()
                return false to "ZIP 内没有文件"
            }
            ensureIndexAtTop(staging)
            dest.deleteRecursively()
            if (!staging.renameTo(dest)) {
                staging.copyRecursively(dest, overwrite = true)
                staging.deleteRecursively()
            }
            // ensure port exists for new sites
            sitePort(ctx, name)
            // if the site was running before the update, bring it back up on the new content
            if (wasRunning) {
                SiteServerService.startSiteStatic(ctx, name, sitePort(ctx, name))
            }
            return true to "已导入 $name（${humanSize(total)}，端口 ${sitePort(ctx, name)}）"
        } catch (e: Exception) {
            staging.deleteRecursively()
            return false to "解压失败：${e.message}"
        }
    }

    private fun ensureIndexAtTop(dir: File) {
        val hasTop = File(dir, "index.html").isFile
        if (hasTop) return
        val kids = dir.listFiles()?.filter { it.isDirectory } ?: return
        if (kids.size == 1) {
            val inner = kids[0]
            if (File(inner, "index.html").isFile) {
                val tmp = File(dir.parentFile, ".lift.tmp")
                tmp.deleteRecursively()
                if (inner.renameTo(tmp)) {
                    dir.deleteRecursively()
                    tmp.renameTo(dir)
                }
            }
        }
    }

    private fun commonPrefixFirst(firstEntry: ZipEntry?): String {
        val n = firstEntry?.name ?: return ""
        val slash = n.indexOf('/')
        return if (slash > 0) n.substring(0, slash + 1) else ""
    }

    fun sanitizeName(s: String): String =
        s.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.').take(60)

    // ---------- url fetch ----------

    fun importUrlAsync(
        ctx: Context,
        urlStr: String,
        name: String,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        io.launch {
            val r =
                withContext(Dispatchers.IO) {
                    try {
                        val tmp = File(ctx.cacheDir, "dl_$name.zip")
                        val conn = URL(urlStr).openConnection() as HttpURLConnection
                        conn.connectTimeout = 15000
                        conn.readTimeout = 60000
                        conn.instanceFollowRedirects = true
                        conn.connect()
                        val total = conn.contentLengthLong
                        conn.inputStream.use { input ->
                            FileOutputStream(tmp).use { fout ->
                                val buf = ByteArray(128 * 1024)
                                var read = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    fout.write(buf, 0, n)
                                    read += n
                                    if (total > 0) onProgress((read * 100 / total).toInt().coerceIn(0, 100))
                                }
                            }
                        }
                        conn.disconnect()
                        val res = importZip(ctx, tmp, name)
                        tmp.delete()
                        if (res.first) setImportSource(ctx, name, "url", urlStr)
                        res
                    } catch (e: Exception) {
                        false to "下载失败：${e.message}"
                    }
                }
            withContext(Dispatchers.Main) { onDone(r.first, r.second) }
        }
    }

    // ---------- migration ----------

    fun migrateAsync(
        ctx: Context,
        newRoot: File,
        onProgress: (phase: String, percent: Int) -> Unit,
        onDone: (Boolean, String) -> Unit,
    ) {
        io.launch {
            val r =
                withContext(Dispatchers.IO) {
                    try {
                        val oldRoot = rootDir(ctx)
                        if (oldRoot.canonicalPath == newRoot.canonicalPath) {
                            return@withContext true to "目录未变化"
                        }
                        // guard: newRoot inside oldRoot (or the reverse) would make the
                        // final oldRoot.deleteRecursively() eat the migrated data
                        val oldCanon = oldRoot.canonicalPath + File.separator
                        val newCanon = newRoot.canonicalPath + File.separator
                        if (newRoot.canonicalPath.startsWith(oldCanon) ||
                            oldRoot.canonicalPath.startsWith(newCanon)
                        ) {
                            return@withContext false to "目标目录与当前目录存在嵌套关系，无法迁移（原目录保留）"
                        }
                        newRoot.mkdirs()
                        val res = Migrator.migrate(oldRoot, newRoot, onProgress)
                        if (res.errors.isNotEmpty()) {
                            return@withContext false to "迁移失败 ${res.errors.size} 项：${res.errors.first()}（原目录保留）"
                        }
                        setRoot(ctx, newRoot)
                        oldRoot.deleteRecursively()
                        true to "已迁移 ${res.movedFiles} 个文件（${humanSize(res.movedBytes)}）"
                    } catch (e: Exception) {
                        false to "迁移失败：${e.message}（原目录保留）"
                    }
                }
            withContext(Dispatchers.Main) { onDone(r.first, r.second) }
        }
    }

    fun treeUriToPath(ctx: Context, uri: Uri): String? =
        try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val decoded = Uri.decode(docId.removePrefix("tree:"))
            val split = decoded.indexOf(':')
            if (split <= 0) null else {
                val volume = decoded.substring(0, split)
                val sub = decoded.substring(split + 1)
                val base =
                    when (volume) {
                        "primary" -> Environment.getExternalStorageDirectory().absolutePath
                        else -> "/storage/$volume"
                    }
                if (sub.isBlank()) base else "$base/$sub"
            }
        } catch (e: Exception) {
            null
        }

    fun takeTreeUri(ctx: Context, uri: Uri): File? {
        try {
            val flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {}
        val path = treeUriToPath(ctx, uri) ?: return null
        val f = File(path)
        return if (f.isDirectory) f else null
    }

    // ---------- helpers ----------

    // ---------- config export / import (JSON) ----------

    /** Snapshot of everything needed to restore configuration on another install. */
    fun exportConfigJson(ctx: Context): String {
        val p = prefs(ctx)
        val root = org.json.JSONObject()
        root.put("app", "PocketSite")
        root.put("schema", 1)
        root.put("rootPath", rootDir(ctx).absolutePath)
        root.put("appVersion", try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        })
        val sites = org.json.JSONArray()
        for (s in listSites(ctx)) {
            val o = org.json.JSONObject()
            o.put("name", s.name)
            o.put("port", sitePort(ctx, s.name))
            o.put("autostart", autostart(ctx, s.name))
            o.put("active", s.name in SiteServerService.runningSites())
            importSource(ctx, s.name)?.let { (kind, value) ->
                o.put("sourceKind", kind)
                o.put("sourceValue", value)
            }
            o.put("sizeBytes", s.sizeBytes)
            o.put("fileCount", s.fileCount)
            sites.put(o)
        }
        root.put("sites", sites)
        return root.toString(2)
    }

    /**
     * Apply a config JSON: restores root path, ports, autostart flags and import
     * sources. Site CONTENT (the directories themselves) is not transferred —
     * only metadata; content is expected to be re-imported from the recorded
     * source (url) or the same zip location.
     */
    data class ConfigImportResult(
        val ok: Boolean,
        val message: String,
        val needFetch: List<Pair<String, String>>, // name to url — missing content, url-sourced
        val toStart: List<String>,                 // sites flagged active in the config
        val manualNeeded: List<String>,            // uri-sourced sites missing content (must re-import zip by hand)
    )

    fun importConfigJson(ctx: Context, json: String): ConfigImportResult {
        return try {
            val root = org.json.JSONObject(json)
            if (root.optString("app") != "PocketSite")
                return ConfigImportResult(false, "不是 PocketSite 配置文件", emptyList(), emptyList(), emptyList())

            // ---- root-path resolution (safety first) ----
            // Priority: keep current custom dir (already authorized) > imported path
            // (only usable if storage permission granted AND the dir exists) > default.
            val configRoot = root.optString("rootPath", "")
            var rootNote = ""
            if (configRoot.isNotBlank()) {
                val currentCustom = prefs(ctx).getString(KEY_ROOT, null)
                if (currentCustom != null) {
                    // already on a custom dir → keep it, imported path ignored
                    rootNote = "；已保留当前自定义根目录（配置中的 ${configRoot} 未应用）"
                } else if (Environment.isExternalStorageManager() && File(configRoot).exists()) {
                    // permission granted and dir exists → safe to adopt
                    setRoot(ctx, File(configRoot))
                    rootNote = "；已切换到配置中的根目录"
                } else {
                    // no permission yet or dir missing → stay on default, tell the user
                    rootNote = "；配置中的自定义根目录未应用（缺少存储权限或目录不存在）"
                }
            }
            val sites = root.optJSONArray("sites")
            var applied = 0
            if (sites != null) {
                for (i in 0 until sites.length()) {
                    val o = sites.getJSONObject(i)
                    val name = sanitizeName(o.optString("name", ""))
                    if (name.isEmpty()) continue
                    val port = o.optInt("port", -1)
                    if (port in PORT_MIN..PORT_MAX) setSitePort(ctx, name, port)
                    setAutostart(ctx, name, o.optBoolean("autostart", false))
                    val kind = o.optString("sourceKind", "")
                    val value = o.optString("sourceValue", "")
                    if (kind.isNotBlank() && value.isNotBlank()) {
                        setImportSource(ctx, name, kind, value)
                    }
                    applied++
                }
            }
            // sites flagged "active" in the config should be running after import
            val toStart = mutableListOf<String>()
            if (sites != null) {
                for (i in 0 until sites.length()) {
                    val o = sites.getJSONObject(i)
                    val name = sanitizeName(o.optString("name", ""))
                    if (name.isEmpty()) continue
                    if (o.optBoolean("active", false)) toStart.add(name)
                }
            }
            // which url-sourced sites are missing content on this device?
            val needFetch = mutableListOf<Pair<String, String>>() // name to url
            if (sites != null) {
                for (i in 0 until sites.length()) {
                    val o = sites.getJSONObject(i)
                    val name = sanitizeName(o.optString("name", ""))
                    if (name.isEmpty()) continue
                    if (o.optString("sourceKind") != "url") continue
                    val dir = java.io.File(rootDir(ctx), name)
                    val hasContent = dir.isDirectory && File(dir, "index.html").isFile
                    if (!hasContent) {
                        needFetch.add(name to o.optString("sourceValue", ""))
                    }
                }
            }
            root.put("needFetch", org.json.JSONArray(needFetch.map { (n, u) ->
                org.json.JSONObject().put("name", n).put("url", u)
            }))
            // uri-sourced sites missing content: diagnose why auto-restore isn't
            // possible so the user knows exactly what to do
            val manualNeeded = mutableListOf<String>()
            if (sites != null) {
                val hasPerm = Environment.isExternalStorageManager()
                for (i in 0 until sites.length()) {
                    val o = sites.getJSONObject(i)
                    val name = sanitizeName(o.optString("name", ""))
                    if (name.isEmpty()) continue
                    if (o.optString("sourceKind") != "uri") continue
                    val dir = java.io.File(rootDir(ctx), name)
                    val hasContent = dir.isDirectory && File(dir, "index.html").isFile
                    if (hasContent) continue
                    // diagnose: decode the recorded uri to a filesystem path and probe it
                    val src = o.optString("sourceValue", "")
                    val reason = if (!hasPerm) {
                        "未授予存储权限，无法访问原 ZIP（授权后重新导入可自动恢复）"
                    } else {
                        // permission granted: resolve /storage/emulated/0/... path from
                        // the content uri and check existence directly
                        val decoded = android.net.Uri.decode(src)
                        val afterPrimary = decoded
                            .substringAfter("document/primary:", "")
                            .ifEmpty { decoded.substringAfter("primary%3A", decoded.substringAfter("primary:")) }
                        val fsPath = "/storage/emulated/0/" + afterPrimary
                        val f = java.io.File(fsPath)
                        if (f.isFile) {
                            // file exists! permission is the only blocker — auto-recover now
                            autoRestoreFromPath(ctx, name, fsPath)
                            continue // recovered; drop from manual list
                        }
                        "原 ZIP（${f.name}）未找到，请手动重新导入"
                    }
                    manualNeeded.add("$name（$reason）")
                }
            }
            val msg = buildString {
                append("已导入配置（$applied 个站点）")
                append(rootNote)
                if (needFetch.isNotEmpty()) append("，${needFetch.size} 个 URL 站点将自动下载")
            }
            ConfigImportResult(true, msg, needFetch, toStart, manualNeeded)
        } catch (e: Exception) {
            ConfigImportResult(false, "配置导入失败：${e.message}", emptyList(), emptyList(), emptyList())
        }
    }

    /**
     * Recover a uri-sourced site from a real filesystem path (used when storage
     * permission was granted AFTER a config import recorded the source).
     * Returns human-readable result.
     */
    fun autoRestoreFromPath(ctx: Context, name: String, zipPath: String): String {
        val f = java.io.File(zipPath)
        if (!f.isFile) return "原 ZIP 不存在：$zipPath"
        val r = importZip(ctx, f, name, keepPrefs = true)
        return if (r.first) "「$name」已从原路径自动恢复（${humanSize(f.length())}）" else r.second
    }

    fun humanSize(b: Long): String =
        when {
            b >= 1L shl 30 -> "%.1f GB".format(b.toDouble() / (1L shl 30))
            b >= 1L shl 20 -> "%.1f MB".format(b.toDouble() / (1L shl 20))
            b >= 1L shl 10 -> "%.0f KB".format(b.toDouble() / (1L shl 10))
            else -> "$b B"
        }

    fun copyUriToCache(ctx: Context, uri: Uri, displayName: String): File? =
        try {
            val tmp = File(ctx.cacheDir, sanitizeName(displayName))
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            tmp
        } catch (e: Exception) {
            null
        }
}
