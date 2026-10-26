import org.gradle.api.attributes.java.TargetJvmVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    id("kotlin")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
//    implementation fileTree(dir: "libs", include: ["*.jar"])
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    api(project(":libautomata"))

    implementation(libs.kotlin.stdlib)
    // api: the @Serializable models are part of this module's public surface
    api(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.dagger.hilt.core)
    ksp(libs.dagger.hilt.compiler)

    testImplementation(platform(libs.junit.bom)) {
        because("kotlin-test comes with conflicting junit versions")
    }
    testImplementation(libs.kotlin.test)
    testImplementation(libs.willowtreeapps.assertk)
    testImplementation(libs.mockk)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.test {
    useJUnitPlatform()
}

// Unit tests run on the Gradle toolchain JVM (21) and never reach Android, so they may target a
// newer bytecode level than the library itself, which stays at 11 for `app`. JUnit >= 6 ships
// Java 17 bytecode, and Gradle's variant resolution rejects it against a Java 11 test compilation.
tasks.compileTestKotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Kotlin refuses to compile when the paired Java task disagrees, even with no Java test sources.
tasks.compileTestJava {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
    options.release.set(17)
}

listOf(configurations.testCompileClasspath, configurations.testRuntimeClasspath).forEach { config ->
    config.configure {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
        }
    }
}

// Official-data report tool, opt-in network access; never run by unit tests or on the game device.
tasks.register<JavaExec>("weeklyRouteReport") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.fate_grand_automata.scripts.WeeklyRouteReportKt")
    args(
        providers.gradleProperty("atlasCache").getOrElse("${rootProject.layout.buildDirectory.get()}/atlas-weekly"),
        "${rootProject.layout.buildDirectory.get()}/weekly-report-utf8.txt"
    )
    maxHeapSize = "2g"
}

tasks.register<JavaExec>("weeklyRouteStress") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.fate_grand_automata.scripts.WeeklyRouteStress")
    args(providers.gradleProperty("atlasCache").getOrElse("${rootProject.layout.buildDirectory.get()}/atlas-weekly"))
    maxHeapSize = "2g"
}

// Offline append measurement uses the product matcher, excluding every cell from a held-out frame.
tasks.register<JavaExec>("panelAppendEvaluation") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.fate_grand_automata.scripts.PanelAppendEvaluation")
    args(rootProject.projectDir.absolutePath,
        "${rootProject.layout.buildDirectory.get()}/panel-append-evaluation.json")
}
