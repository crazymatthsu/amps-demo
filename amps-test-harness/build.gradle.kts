/**
 * amps-test-harness: starts a throwaway AMPS container for integration tests.
 *
 * Extracted because three modules (fix42-publisher, cache-persistent-store,
 * hazelcast-persistent-store) each had their own ~290-line copy of the same
 * harness, differing only in which server flow to run, what to name the
 * container, and whether the client URI selects the `fix` or `json` message
 * type. Those four values are now an {@link AmpsFlow} and the rest is shared.
 *
 * Two backends behind one interface, chosen with AMPS_TEST_HARNESS -- see
 * AmpsTestServer. Consumers depend on this from their integrationTest source
 * set and reference nothing but AmpsTestServer and AmpsFlow, so Testcontainers
 * stays an implementation detail of this module.
 *
 * Not published: it exists to be consumed by sibling modules' test suites.
 */
plugins {
    `java-library`
}

dependencies {
    // Part of the contract: consumers hold an AmpsTestServer and read its logs.
    api(libs.slf4j.api)

    // The Testcontainers backend. `implementation`, not `api`: consumers never
    // name a Testcontainers type -- they pick a backend with an environment
    // variable, not by importing one.
    implementation(libs.testcontainers)
}
