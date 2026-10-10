import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
    kotlin("jvm") version "2.0.20"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = "com.artie"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    // bdd-gates: Gherkin scenarios drive the work. Cucumber 7 fails undefined
    // steps by default; keep it that way so a new scenario goes red.
    testImplementation(platform("io.cucumber:cucumber-bom:7.18.0"))
    testImplementation("io.cucumber:cucumber-java")
    testImplementation("io.cucumber:cucumber-junit-platform-engine")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-suite")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // bdd-gates: the core boundary as a failing test.
    testImplementation("com.lemonappdev:konsist:0.17.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Greenfield: Detekt at its own defaults, no baseline.
detekt {
    buildUponDefaultConfig = true
}

// detekt 1.23.8 is built against Kotlin 2.0.21; keep its own classpath on that version.
configurations.matching { it.name == "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
    }
}

// bdd-gates: records "BDD_RUN PASS|FAIL" after every test run, including failed ones.
val logBddRun by tasks.registering {
    group = "verification"
    description = "Appends a BDD_RUN fact to .claude/tdd-events.log for the pre-commit TDD-ordering check."
    doLast {
        val resultFile = file("build/test-results/test/TEST-com.artie.buster.bdd.RunCucumberTest.xml")
        val logFile = file(".claude/tdd-events.log")
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        logFile.parentFile.mkdirs()
        // A compile failure stops the build before tests run, which leaves the
        // previous run's XML in place. Log it as red instead of reading stale results.
        val compileFailed = listOf("compileKotlin", "compileTestKotlin")
            .any { tasks.named(it).get().state.failure != null }
        if (compileFailed) {
            logFile.appendText("$timestamp BDD_RUN FAIL module=buster reason=compile\n")
            return@doLast
        }
        if (!resultFile.exists()) {
            // No result file means compilation or discovery failed: still red.
            logFile.appendText("$timestamp BDD_RUN FAIL module=buster reason=no-results\n")
            return@doLast
        }
        val xml = resultFile.readText()
        fun attr(name: String) = Regex("""$name="(\d+)"""").find(xml)?.groupValues?.get(1) ?: "0"
        val status = if (attr("failures") == "0" && attr("errors") == "0") "PASS" else "FAIL"
        logFile.appendText(
            "$timestamp BDD_RUN $status module=buster total=${attr("tests")} failures=${attr("failures")} errors=${attr("errors")}\n",
        )
    }
}

tasks.test {
    finalizedBy(logBddRun)
}

// Red includes "does not compile": finalize the compile tasks too, so that
// failure is logged. Gradle runs logBddRun once, after whichever ran.
tasks.named("compileKotlin") { finalizedBy(logBddRun) }
tasks.named("compileTestKotlin") { finalizedBy(logBddRun) }
