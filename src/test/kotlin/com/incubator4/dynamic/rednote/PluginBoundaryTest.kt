package com.incubator4.dynamic.rednote

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginBoundaryTest {
    @Test
    fun `plugin sources should not import runtime or storage implementations`() {
        val roots = listOf(Path.of("src/main/kotlin"), Path.of("src/test/kotlin"))
        val forbiddenImports = listOf(
            "top.colter.dynamic.core." + "repository",
            "top.colter.dynamic.core." + "table",
            "top.colter.dynamic." + "repository",
            "top.colter.dynamic." + "table",
            "top.colter.dynamic." + "plugin",
            "top.colter.dynamic." + "event",
        )

        val offenders = roots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                    .flatMap { path ->
                        val content = Files.readString(path)
                        forbiddenImports
                            .filter { content.contains(it) }
                            .map { "${root.relativize(path)} -> $it" }
                            .stream()
                    }
                    .toList()
            }
        }
            .sorted()

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `plugin sources should use incubator4 package not official colter package`() {
        val roots = listOf(Path.of("src/main/kotlin"), Path.of("src/test/kotlin"))
        val forbiddenPackage = "package top.colter.dynamic." + "rednote"
        val forbiddenPath = "/top/colter/dynamic/" + "rednote/"
        val offenders = roots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                    .filter { path ->
                        val content = Files.readString(path)
                        content.contains(forbiddenPackage) || path.toString().contains(forbiddenPath)
                    }
                    .map { root.relativize(it).toString() }
                    .toList()
            }
        }
            .sorted()

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `plugin descriptor should identify rednote publisher`() {
        val yaml = javaClass.classLoader.getResource("plugin.yml")
        assertNotNull(yaml, "缺少 plugin.yml")
        val content = yaml.readText()
        assertTrue(content.contains("id: rednote-publisher"))
        assertTrue(content.contains("mainClass: com.incubator4.dynamic.rednote.RednotePublisherPlugin"))
        assertTrue(content.contains("apiVersion: 3.0.0"))
    }
}
