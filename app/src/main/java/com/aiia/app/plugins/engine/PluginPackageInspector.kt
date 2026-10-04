package com.aiia.app.plugins.engine

import java.io.File
import java.util.zip.ZipInputStream

/**
 * Validation for `.aiip` packages before anything touches the filesystem.
 *
 * Extracted purely so the traversal and size rules can be unit-tested: the previous
 * implementation enforced them inline in [PluginManager], where they were unreachable
 * from tests.
 */
object PluginPackageInspector {
    const val MAX_PACKAGE_BYTES = 256L * 1024 * 1024

    class UnsafeEntryException(message: String) : IllegalArgumentException(message)

    class PackageTooLargeException(message: String) : IllegalArgumentException(message)

    /**
     * Resolves [entryName] against [target] and refuses anything that would escape it.
     *
     * Handles absolute paths, `..` traversal, and Windows-style separators that some
     * archivers emit.
     */
    fun resolveEntry(target: File, entryName: String): File {
        if (entryName.isBlank()) throw UnsafeEntryException("Empty zip entry name")
        if (entryName.startsWith("/") || entryName.startsWith("\\") || WINDOWS_DRIVE.matches(entryName)) {
            throw UnsafeEntryException("Absolute zip path is not allowed: $entryName")
        }
        val normalized = entryName.replace('\\', '/')
        val destination = File(target, normalized)
        val root = target.canonicalPath
        val resolved = destination.canonicalPath
        if (resolved != root && !resolved.startsWith(root + File.separator)) {
            throw UnsafeEntryException("Unsafe zip path: $entryName")
        }
        return destination
    }

    private val WINDOWS_DRIVE = Regex("""^[A-Za-z]:.*""")

    fun requireWithinSizeLimit(totalBytes: Long) {
        if (totalBytes > MAX_PACKAGE_BYTES) {
            throw PackageTooLargeException(
                "Package is too large: $totalBytes bytes (limit $MAX_PACKAGE_BYTES)"
            )
        }
    }

    /**
     * Extracts [source] into [target] enforcing the rules above.
     *
     * Returns the number of bytes written to disk.
     */
    fun extract(source: File, target: File): Long {
        target.deleteRecursively()
        if (!target.mkdirs() && !target.isDirectory) {
            throw UnsafeEntryException("Cannot create ${target.path}")
        }
        var total = 0L
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = resolveEntry(target, entry.name)
                if (entry.isDirectory) {
                    destination.mkdirs()
                } else {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { out -> zip.copyTo(out) }
                    total += destination.length()
                    requireWithinSizeLimit(total)
                }
                zip.closeEntry()
            }
        }
        return total
    }
}
