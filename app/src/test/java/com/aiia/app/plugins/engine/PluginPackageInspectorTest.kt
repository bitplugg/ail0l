package com.aiia.app.plugins.engine

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginPackageInspectorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `accepts a normal relative entry`() {
        val root = temp.newFolder("pkg")
        val resolved = PluginPackageInspector.resolveEntry(root, "assets/data.txt")
        assertTrue(resolved.canonicalPath.startsWith(root.canonicalPath + File.separator))
    }

    @Test(expected = PluginPackageInspector.UnsafeEntryException::class)
    fun `rejects parent traversal`() {
        PluginPackageInspector.resolveEntry(temp.newFolder("pkg"), "../escape.txt")
    }

    @Test(expected = PluginPackageInspector.UnsafeEntryException::class)
    fun `rejects deep parent traversal`() {
        PluginPackageInspector.resolveEntry(temp.newFolder("pkg"), "a/b/../../../escape.txt")
    }

    @Test(expected = PluginPackageInspector.UnsafeEntryException::class)
    fun `rejects backslash traversal`() {
        PluginPackageInspector.resolveEntry(temp.newFolder("pkg"), "..\\escape.txt")
    }

    @Test(expected = PluginPackageInspector.UnsafeEntryException::class)
    fun `rejects an absolute path`() {
        PluginPackageInspector.resolveEntry(temp.newFolder("pkg"), "/etc/passwd")
    }

    @Test(expected = PluginPackageInspector.UnsafeEntryException::class)
    fun `rejects a blank entry name`() {
        PluginPackageInspector.resolveEntry(temp.newFolder("pkg"), "")
    }

    @Test
    fun `accepts a dot prefixed directory inside the package`() {
        val root = temp.newFolder("pkg")
        val resolved = PluginPackageInspector.resolveEntry(root, "./plugin.dex")
        assertEquals(File(root, "plugin.dex").canonicalPath, resolved.canonicalPath)
    }

    @Test
    fun `size limit accepts values under the cap`() {
        PluginPackageInspector.requireWithinSizeLimit(PluginPackageInspector.MAX_PACKAGE_BYTES)
    }

    @Test(expected = PluginPackageInspector.PackageTooLargeException::class)
    fun `size limit rejects oversized packages`() {
        PluginPackageInspector.requireWithinSizeLimit(PluginPackageInspector.MAX_PACKAGE_BYTES + 1)
    }

    @Test
    fun `extracts a well formed package`() {
        val archive = writeZip("good.aiip", mapOf("manifest.json" to "{}", "plugin.dex" to "dex"))
        val target = File(temp.root, "out-good")

        val written = PluginPackageInspector.extract(archive, target)

        assertTrue(File(target, "manifest.json").isFile)
        assertTrue(File(target, "plugin.dex").isFile)
        assertEquals(5L, written)
    }

    @Test
    fun `extract creates nested directories`() {
        val archive = writeZip("nested.aiip", mapOf("assets/deep/file.txt" to "hello"))
        val target = File(temp.root, "out-nested")

        PluginPackageInspector.extract(archive, target)

        assertEquals("hello", File(target, "assets/deep/file.txt").readText())
    }

    @Test
    fun `extract refuses a traversal entry and writes nothing outside`() {
        val archive = writeZip("evil.aiip", mapOf("../escaped.txt" to "pwned"))
        val target = File(temp.root, "out-evil")

        val failure = runCatching { PluginPackageInspector.extract(archive, target) }.exceptionOrNull()

        assertTrue(failure is PluginPackageInspector.UnsafeEntryException)
        assertTrue(!File(temp.root, "escaped.txt").exists())
    }

    @Test
    fun `extract clears a previous run`() {
        val archive = writeZip("first.aiip", mapOf("a.txt" to "1"))
        val target = File(temp.root, "out-reuse")
        PluginPackageInspector.extract(archive, target)
        assertTrue(File(target, "a.txt").isFile)

        val second = writeZip("second.aiip", mapOf("b.txt" to "2"))
        PluginPackageInspector.extract(second, target)

        assertTrue(!File(target, "a.txt").exists())
        assertTrue(File(target, "b.txt").isFile)
    }

    private fun writeZip(name: String, entries: Map<String, String>): File {
        val archive = File(temp.root, name)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return archive
    }
}
