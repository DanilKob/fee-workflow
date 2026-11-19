package com.cashi.fee

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * Every acceptance criterion in `docs/requirements.md` has a tagged Cucumber scenario, or is on
 * [NOT_YET]. The criteria are read from the requirements file, so a new one needs a scenario.
 * A tag or a [NOT_YET] entry that names no criterion fails, and so does a criterion that is both
 * tagged and on the list.
 */
class TraceabilityTest {

    private val features: List<Path> =
        Files.walk(FEATURES).use { paths -> paths.filter { it.isRegularFile() && it.extension == "feature" }.toList() }

    /** The acceptance criteria `docs/requirements.md` defines, by number. */
    private val allAcs: Set<Int> by lazy {
        AC_DEFINITION.findAll(REQUIREMENTS.readText()).map { it.groupValues[1].toInt() }.toSet()
    }

    /** Criterion number → its tagged scenarios. A tag on the feature or an Examples block counts. */
    private val scenariosByAc: Map<Int, List<String>> by lazy {
        val found = mutableMapOf<Int, MutableList<String>>()
        features.forEach { file ->
            var pendingTags = emptySet<Int>()
            var featureTags = emptySet<Int>()
            var scenario = ""
            file.readLines().map(String::trim).forEach { line ->
                when {
                    line.startsWith("@") -> pendingTags = pendingTags + AC_TAG.findAll(line).map { it.groupValues[1].toInt() }
                    line.startsWith("Feature:") -> featureTags = pendingTags.also { pendingTags = emptySet() }
                    SCENARIO.matches(line) || line.startsWith("Examples:") -> {
                        if (!line.startsWith("Examples:")) scenario = "${file.name}: ${line.substringAfter(':').trim()}"
                        (featureTags + pendingTags).forEach { found.getOrPut(it) { mutableListOf() } += scenario }
                        pendingTags = emptySet()
                    }
                }
            }
        }
        found
    }

    @Test
    fun `the feature files were found`() {
        assertTrue(features.isNotEmpty()) { "no .feature files under $FEATURES" }
    }

    /** Without this, a parse that finds nothing would let every other check pass vacuously. */
    @Test
    fun `the acceptance criteria were read from the requirements, numbered from 1 with no gap`() {
        assertTrue(allAcs.isNotEmpty()) { "no '- **ACn.**' line in $REQUIREMENTS" }
        assertEquals((1..allAcs.max()).toSet(), allAcs) { "criteria read from $REQUIREMENTS: ${allAcs.sorted()}" }
    }

    @Test
    fun `every acceptance criterion has a tagged scenario or is not yet due`() {
        val missing = allAcs.sorted().filter { it !in scenariosByAc && it !in NOT_YET }

        assertEquals(emptyList<Int>(), missing) { "acceptance criteria with no tagged scenario: ${missing.map { "AC$it" }}" }
    }

    /** Every criterion is built, so a new one must arrive with its scenario. */
    @Test
    fun `NOT_YET is empty, every slice is built`() {
        assertEquals(emptySet<Int>(), NOT_YET)
    }

    @Test
    fun `NOT_YET lists only criteria that still have no scenario`() {
        val covered = NOT_YET.filter { it in scenariosByAc }

        assertEquals(emptyList<Int>(), covered) { "now covered, remove from NOT_YET: ${covered.map { "AC$it" }}" }
    }

    @Test
    fun `NOT_YET lists only criteria the requirements define`() {
        val unknown = NOT_YET.filter { it !in allAcs }

        assertEquals(emptyList<Int>(), unknown) { "on NOT_YET but not in $REQUIREMENTS: ${unknown.map { "AC$it" }}" }
    }

    @Test
    fun `every AC tag names a real acceptance criterion`() {
        val unknown = scenariosByAc.keys.filter { it !in allAcs }

        assertEquals(emptyList<Int>(), unknown) { "tags with no acceptance criterion: ${unknown.map { "AC$it" }}" }
    }

    private companion object {
        /** The project base directory: Maven's `basedir`, or the working directory when run elsewhere. */
        val BASE_DIR: Path = (System.getProperty("basedir")?.let { Path.of(it) } ?: Path.of("")).toAbsolutePath()
        val FEATURES: Path = BASE_DIR.resolve(Path.of("src", "test", "resources", "features"))
        val REQUIREMENTS: Path = BASE_DIR.resolve(Path.of("docs", "requirements.md"))

        /** The line that defines a criterion: `- **AC<n>.** …`. */
        val AC_DEFINITION = Regex("""^- \*\*AC(\d+)\.\*\*""", RegexOption.MULTILINE)
        val AC_TAG = Regex("""@AC(\d+)\b""")
        val SCENARIO = Regex("""^(Scenario|Scenario Outline|Example|Scenario Template):.*""")

        /** Criteria allowed to have no scenario yet. Empty: every criterion has one. */
        val NOT_YET: Set<Int> = emptySet()
    }
}
