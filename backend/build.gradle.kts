plugins {
    id("java")
    id("checkstyle")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
}

group = "com.bifos"
version = "0.1.0-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_21

springBoot {
    buildInfo()
}

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
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.h2.database)
}

tasks.test {
    useJUnitPlatform()
}

/**
 * ArchUnit 기준 파일을 쓰는 설정은 Gradle 속성으로만 켠다.
 * 같은 이름의 JVM 시스템 속성으로 넘기면 ArchUnit 이 archunit.properties 값 대신 쓴다.
 * 갱신 방법은 backend/AGENTS.md 의 「구조 규칙」 절에 있다.
 */
val archunitFreezeProperties = listOf(
    "archunit.freeze.refreeze",
    "archunit.freeze.store.default.allowStoreCreation",
    "archunit.freeze.store.default.allowStoreUpdate",
)

tasks.withType<Test>().configureEach {
    archunitFreezeProperties.forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    // 기준 파일만 바뀌어도 테스트가 옛 결과로 건너뛰지 않게 입력으로 둔다.
    // inputs.dir 은 디렉터리가 없으면 태스크 검증이 실패해 기준을 처음 만들 수 없다.
    inputs.files(fileTree("config/archunit/store"))
        .withPropertyName("archunitStore")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("archTest") {
    group = "verification"
    description = "ArchUnit 구조 규칙만 검사한다."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("architecture")
    }
}

/**
 * 코드 규칙은 config/checkstyle 의 설정 파일 셋이 갖는다.
 * error 는 태스크를 실패시키고 warning 은 보고서에만 남긴다.
 * 규칙과 기준 갱신 방법은 backend/AGENTS.md 의 「코드 규칙」 절에 있다.
 */
checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    isIgnoreFailures = false
    maxWarnings = Int.MAX_VALUE
}

tasks.withType<Checkstyle>().configureEach {
    reports {
        xml.required = true
        html.required = false
    }
}

/**
 * Java 포맷은 Spotless 와 palantir-java-format 이 정한다.
 * ratchetFrom 은 HEAD 와 origin/main 의 공통 조상에서 바뀐 파일만 검사하고 고친다.
 * 저장소 전체를 한 번에 바꾸지 않고, 파일을 처음 고칠 때 그 파일 전체가 포맷된다.
 * 선택 까닭은 ADR-040 에 있고, 사용법은 backend/AGENTS.md 의 「포맷」 절에 있다.
 */
spotless {
    ratchetFrom("origin/main")
    java {
        target("src/main/java/**/*.java", "src/test/java/**/*.java")
        palantirJavaFormat(libs.versions.palantir.java.format.get())
        trimTrailingWhitespace()
        endWithNewline()
    }
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
