/**
 * The server module carries no Java: it is the AMPS instance itself -- config,
 * container image recipe, and lifecycle. The Gradle tasks here are thin wrappers
 * over server/scripts/amps.sh so that `./gradlew serverStart` works for people
 * who never leave the build tool.
 */

plugins {
    base
}

val ampsScript = layout.projectDirectory.file("scripts/amps.sh").asFile

fun ampsTask(name: String, argument: String, describe: String) =
    tasks.register<Exec>(name) {
        group = "amps server"
        description = describe
        commandLine(ampsScript.absolutePath, argument)
        // The script reports "not running" with a non-zero exit, which is
        // information rather than a build failure.
        isIgnoreExitValue = name == "serverStatus"
    }

ampsTask("serverStart", "start", "Start the AMPS instance in podman (or docker).")
ampsTask("serverStop", "stop", "Stop and remove the AMPS container, keeping its data.")
ampsTask("serverRestart", "restart", "Restart the instance on the same data, exercising recovery.")
ampsTask("serverStatus", "status", "Report whether AMPS is up and on which ports.")
ampsTask("serverWait", "wait", "Block until the instance accepts client connections.")
ampsTask("serverReset", "reset", "Stop the instance and delete all SOW and journal data.")
ampsTask("serverProbe", "probe", "Inspect the image to locate the ampServer binary.")

tasks.register<Exec>("serverLogs") {
    group = "amps server"
    description = "Print the AMPS server log."
    commandLine(ampsScript.absolutePath, "logs", "--tail", "200")
}

tasks.register<Exec>("serverValidateConfig") {
    group = "amps server"
    description = "Ask ampServer to parse the config without starting the instance."
    commandLine(ampsScript.absolutePath, "validate")
}
