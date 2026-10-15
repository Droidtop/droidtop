// Test-only: builds throwaway X.509 chains and JWS signatures for the unit tests of the
// certificate signature format (docs/SPEC.md 12a "Signatures: certificate chains to Droidtop
// Core"). Never a dependency of anything shipped; every key it makes lives only in a test run.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.bouncycastle.bcpkix)
    // Conscrypt is the provider on a device; ChainSignatureTest runs the verifier under it too.
    api(libs.conscrypt.openjdk.uber)
}
