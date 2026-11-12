package com.example.xmu_sports_booking.local

import android.content.Context
import java.io.File

/** Never touches databases, preferences, Keystore material or the packaged recognition model. */
object LocalStorage {
    fun clean(context: Context): Long {
        val roots = mutableListOf(File(context.filesDir, "private-diagnostics"))
        val hasKernel = runCatching {
            context.assets.open("flutter_assets/kernel_blob.bin").use { true }
        }.getOrDefault(false)
        if (!hasKernel) roots.add(File(context.dataDir, "app_flutter/flutter_assets"))
        return roots.sumOf { removeOwnedTree(context.dataDir, it) }
    }

    internal fun removeOwnedTree(base: File, target: File): Long {
        val root = base.canonicalFile
        val resolved = target.canonicalFile
        check(resolved.path.startsWith(root.path + File.separator)) { "INVALID_CLEANUP_PATH" }
        // Do not follow links while traversing cache content.
        fun remove(file: File): Long {
            if (file.canonicalPath != file.absolutePath) return 0L
            if (file.isDirectory) return (file.listFiles()?.sumOf { remove(it) } ?: 0L).also { file.delete() }
            val bytes = file.length()
            return if (file.delete()) bytes else 0L
        }
        return remove(resolved)
    }
}
