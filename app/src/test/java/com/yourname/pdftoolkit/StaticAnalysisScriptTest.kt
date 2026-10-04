package com.yourname.pdftoolkit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Behavioral test for scripts/static_analysis.sh exit codes.
 * The script must exit non-zero when a hard error is planted, and 0 on a clean tree.
 */
class StaticAnalysisScriptTest {

    @Test
    fun `script fails on planted hardcoded path and passes when clean`() {
        val moduleDir = File("").absoluteFile
        val projectRoot = if (File(moduleDir, "scripts/static_analysis.sh").exists()) moduleDir
            else moduleDir.parentFile ?: moduleDir
        val script = File(projectRoot, "scripts/static_analysis.sh")
        if (!script.exists()) return // environment without repo checkout: skip

        val srcRoot = if (File(projectRoot, "app/src").exists())
            File(projectRoot, "app/src") else File(projectRoot, "src")
        val probe = File(srcRoot, "main/java/com/yourname/pdftoolkit/util/__ProbeForStaticAnalysis.kt")
        try {
            probe.writeText("val probePath = \"/sdcard/probe.pdf\"\n")
            val failCode = runScript(projectRoot)
            assertEquals("script must exit non-zero with planted error", 1, failCode)
        } finally {
            probe.delete()
        }

        val okCode = runScript(projectRoot)
        assertEquals("script must exit zero on clean tree", 0, okCode)
    }

    private fun runScript(workDir: File): Int {
        val pb = ProcessBuilder("bash", "scripts/static_analysis.sh")
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        val process = pb.start()
        process.inputStream.bufferedReader().readText()
        return process.waitFor()
    }
}
