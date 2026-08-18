/**
 * The docs module holds prose, not code. It is a Gradle subproject so that the
 * documentation is a first-class part of the build rather than a folder people
 * forget: `./gradlew :docs:check` fails if a document is missing or if a link
 * points at a file that does not exist.
 */

plugins {
    base
}

val docsDir = layout.projectDirectory.dir("src")

/** Every document the README and the demos point readers at. */
val requiredDocuments = listOf(
    "amps-vs-kafka.md",
    "transaction-log-sizing.md",
    "high-volume-market-data.md",
    "sow-and-recovery.md",
    "delta-updates.md",
    "protobuf-json-and-amps.md",
    "fix-order-state.md",
    "native-fix-and-nvfix.md",
    "deploying-utils-to-linux.md",
    "runbook.md"
)

val checkDocs = tasks.register("checkDocs") {
    group = "verification"
    description = "Verify the documentation set is complete and its internal links resolve."

    val sourceDir = docsDir.asFile
    val repoRoot = rootProject.projectDir
    inputs.dir(sourceDir)
    outputs.upToDateWhen { false }

    doLast {
        val problems = mutableListOf<String>()

        requiredDocuments.forEach { name ->
            if (!File(sourceDir, name).isFile) {
                problems += "missing document: docs/src/$name"
            }
        }

        // Relative markdown links are the ones that rot. Resolve each against the
        // repository root or the document's own directory, whichever it looks like.
        val linkPattern = Regex("""\[[^]]*]\((?!https?://|#)([^)#]+)(?:#[^)]*)?\)""")
        sourceDir.walkTopDown().filter { it.extension == "md" }.forEach { document ->
            linkPattern.findAll(document.readText()).forEach { match ->
                val target = match.groupValues[1].trim()
                val relativeToDoc = File(document.parentFile, target)
                val relativeToRoot = File(repoRoot, target)
                if (!relativeToDoc.exists() && !relativeToRoot.exists()) {
                    problems += "${document.name}: broken link -> $target"
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException("documentation problems:\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle("docs: ${requiredDocuments.size} required documents present, links resolve")
    }
}

tasks.named("check") {
    dependsOn(checkDocs)
}
