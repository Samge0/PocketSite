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
    private const val KEY_ACTIVE = "active_site"
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

    fun activeSite(ctx: Context): String = prefs(ctx).getString(KEY_ACTIVE, "") ?: ""

    fun setActiveSite(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, name).apply()
    }

    // ---------- per-site autostart ----------

    fun autostart(ctx: Context, name: String): Boolean =
        prefs(ctx).getBoolean(KEY_AUTOSTART + name, false)

    fun setAutostart(ctx: Context, name: String, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTOSTART + name, v).apply()
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
                .apply()
            if (activeSite(ctx) == name) p.edit().putString(KEY_ACTIVE, "").apply()
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

    private fun importZip(
        ctx: Context,
        zipFile: File,
        rawName: String,
        keepPrefs: Boolean = false,
    ): Pair<Boolean, String> {
        if (!zipFile.isFile || zipFile.length() == 0L) return false to "ZIP 不存在或为空"
        val name = sanitizeName(rawName)
        if (name.isEmpty()) return false to "站点名无效"
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
            if (!keepPrefs && activeSite(ctx).isEmpty()) setActiveSite(ctx, name)
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
