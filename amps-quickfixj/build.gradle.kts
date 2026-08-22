/**
 * Dictionary-driven FIX ↔ NVFIX conversion for AMPS native `fix` / `nvfix`
 * payloads. Standalone library: no AMPS client, no amps-cli.
 */
plugins {
    `java-library`
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
