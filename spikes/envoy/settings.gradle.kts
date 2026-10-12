rootProject.name = "envoy-spike"

// Buster's core, built from the repo above. The spike adds one adapter and no copy of the core.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.artie:buster")).using(project(":"))
    }
}
