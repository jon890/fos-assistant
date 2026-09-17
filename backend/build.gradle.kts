plugins {
    id("java")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.bifos"
version = "0.1.0-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_21

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.bundles.spring.boot.starters)

    runtimeOnly(libs.mysql.connector.j)
    implementation(libs.flyway.mysql)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.bundles.jwt)

    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(libs.bundles.spring.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.h2.database)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

/**
 * Runs the Control Plane on the test runtime classpath so an in-memory database is available.
 * Used by scripts/e2e-smoke.sh; production runs the boot jar with MySQL.
 */
tasks.register<JavaExec>("smokeRun") {
    group = "application"
    description = "Runs the app against an in-memory database for the end-to-end smoke test."
    dependsOn(tasks.classes, tasks.testClasses)
    mainClass.set("com.bifos.assistant.AssistantApplication")
    classpath = sourceSets["test"].runtimeClasspath
}
