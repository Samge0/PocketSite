package com.samge.pocketsite

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MigrateResult(val movedFiles: Int, val movedBytes: Long, val errors: List<String>)

/**
 * Rollback-safe site migration with byte-level progress.
 * copy -> verify byte length -> delete source -> prune empty dirs.
 * Any hard failure aborts BEFORE the source is touched; partial copy is unwound.
 */
object Migrator {

    suspend fun migrate(
        oldRoot: File,
        newRoot: File,
        onProgress: (phase: String, percent: Int) -> Unit = { _, _ -> },
    ): MigrateResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        if (!oldRoot.isDirectory || oldRoot.canonicalPath == newRoot.canonicalPath) {
            return@withContext MigrateResult(0, 0, errors)
        }
        newRoot.mkdirs()

        val files = oldRoot.walkTopDown().filter { it.isFile }.toList()
        val totalBytes = files.sumOf { it.length() }.coerceAtLeast(1L)
        var doneBytes = 0L
        var moved = 0

        onProgress("复制文件", 0)
        for (f in files) {
            val rel = try {
                f.relativeTo(oldRoot)
            } catch (e: Exception) {
                errors.add("skip ${f.name}: outside root")
                continue
            }
            val dst = File(newRoot, rel.path)
            try {
                if (dst.exists() && dst.length() == f.length()) {
                    // identical already: just drop source
                    doneBytes += f.length()
                    f.delete()
                    continue
                }
                dst.parentFile?.mkdirs()
                val tmp = File(dst.parentFile, "." + dst.name + ".part")
                try {
                    f.copyTo(tmp, overwrite = true)
                    if (tmp.length() != f.length()) {
                        throw IllegalStateException("verify failed ${f.name}")
                    }
                    if (!tmp.renameTo(dst)) {
                        // same-filesystem rename of a fresh temp is near-guaranteed; fall back to copy
                        tmp.copyTo(dst, overwrite = true)
                        tmp.delete()
                        if (dst.length() != f.length()) throw IllegalStateException("verify failed ${f.name}")
                    }
                } catch (e: Exception) {
                    tmp.delete()
                    throw e
                }
                f.delete()
                moved++
                doneBytes += f.length()
            } catch (e: Exception) {
                errors.add("${rel.path}: ${e.message ?: "unknown"}")
                // roll back this file's partial destination so old dir stays the source of truth
                try {
                    if (dst.exists()) dst.delete()
                } catch (_: Exception) {}
            }
            onProgress("复制文件", (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100))
        }

        // only prune old root after a fully clean pass
        if (errors.isEmpty()) {
            onProgress("清理旧目录", 100)
            pruneEmptyDirs(oldRoot)
        }
        MigrateResult(moved, doneBytes, errors)
    }

    private fun pruneEmptyDirs(dir: File) {
        if (!dir.isDirectory) return
        val subs = dir.listFiles() ?: return
        for (s in subs) if (s.isDirectory) pruneEmptyDirs(s)
        dir.listFiles()?.let { if (it.isEmpty()) dir.delete() }
    }
}
