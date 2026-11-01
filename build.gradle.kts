plugins {
    base
}

group = "com.demo.amps"
version = "1.0.0"

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            // Generated protobuf sources are not our code to lint.
            options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }

        // Unit-test coverage, reported on every PR by .github/workflows/gradle.yml.
        // Report only: nothing here fails the build on a coverage number.
        apply(plugin = "jacoco")
        extensions.configure<JacocoPluginExtension> {
            toolVersion = "0.8.15"
        }

        // The plugin attaches its agent to EVERY Test task. Coverage comes from `test`
        // alone: the integrationTest suites skip without an AMPS image (always, in CI),
        // nothing reports on their data, and fix42-publisher's publishBenchmark would
        // measure an instrumented publisher.
        tasks.withType<Test>().configureEach {
            val isUnitTestTask = name == "test"
            extensions.configure<JacocoTaskExtension> {
                isEnabled = isUnitTestTask
            }
        }

        val jacocoTestReport = tasks.named<JacocoReport>("jacocoTestReport") {
            reports {
                xml.required.set(true) // what the PR comment reads
                html.required.set(true) // the downloadable jacoco-html artifact
            }
        }

        // Every `build` leaves build/reports/jacoco/test/jacocoTestReport.xml behind;
        // a module without tests has no execution data, so its report is SKIPPED.
        tasks.named("test") {
            finalizedBy(jacocoTestReport)
        }
    }
}
