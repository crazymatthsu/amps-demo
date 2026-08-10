plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

dependencies {
    // protobuf-java is the schema runtime; protobuf-java-util carries JsonFormat,
    // which is what actually turns our protobuf objects into the JSON that goes on
    // the wire. We never call toByteArray() / parseFrom() on the binary encoding.
    api(libs.protobuf.java)
    api(libs.protobuf.java.util)
    api(libs.amps.client)
    api(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

protobuf {
    protoc {
        artifact = libs.protoc.get().toString()
    }
}

// compileJava here also compiles the protoc output, which is machine-written and
// trips a pile of lint warnings we cannot fix. Replace the root project's lint
// settings for this task rather than layering another -Xlint on top.
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs = listOf("-Xlint:none", "-parameters")
}
