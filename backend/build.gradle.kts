import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

plugins {
    id("java")
    id("checkstyle")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
    alias(libs.plugins.openrewrite)
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

    rewrite(platform(libs.openrewrite.recipe.bom))
    rewrite(libs.openrewrite.static.analysis)
    rewrite(libs.openrewrite.migrate.java)
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
 * 구조 규칙, 코드 규칙, 포맷을 한 번에 검사한다. 파일을 바꾸지 않는다.
 * scripts/quality.sh 가 이 태스크와 web 검사를 함께 돌린다.
 */
tasks.register("qualityCheck") {
    group = "verification"
    description = "ArchUnit 구조 규칙, Checkstyle 코드 규칙, Spotless 포맷을 한 번에 검사한다."
    dependsOn("archTest", "checkstyleMain", "checkstyleTest", "spotlessCheck")
}

/**
 * Java 포맷은 Spotless 와 palantir-java-format 이 정한다.
 * ratchetFrom 은 HEAD 와 origin/main 의 공통 조상에서 바뀐 파일만 검사하고 고친다.
 * 저장소 전체를 한 번에 바꾸지 않고, 파일을 처음 고칠 때 그 파일 전체가 포맷된다.
 * 선택 까닭은 ADR-041 에 있고, 사용법은 backend/AGENTS.md 의 「포맷」 절에 있다.
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

/**
 * OpenRewrite 는 Checkstyle 규칙 가운데 기계적으로 고칠 수 있는 것만 고친다. 검사는 Checkstyle 이 한다.
 * 레시피와 Checkstyle 규칙의 짝은 이렇다.
 * - ShortenFullyQualifiedTypeReferences: 전체 이름 참조 금지 (fullyQualifiedName)
 * - NeedBraces: NeedBraces
 * - BlankLines: 메서드와 생성자 사이 빈 줄 (EmptyLineSeparator)
 * - UseSlf4j: 직접 만든 로거 금지 (lombokLogger)
 * rewriteRun 은 저장소 전체를 바꾸므로 직접 부르지 않고 아래 rewriteChanged 로 바뀐 파일에만 결과를 남긴다.
 * 사용법은 backend/AGENTS.md 의 「코드 규칙」 절에 있다.
 */
rewrite {
    activeRecipe(
        "org.openrewrite.java.ShortenFullyQualifiedTypeReferences",
        "org.openrewrite.staticanalysis.NeedBraces",
        "org.openrewrite.java.format.BlankLines",
        "org.openrewrite.java.migrate.lombok.log.UseSlf4j",
    )
    // Java 레시피가 Kotlin 소스로 읽은 이 빌드 스크립트를 방문하다 IndexOutOfBoundsException 으로 멈춘다.
    exclusion("**/*.kts")
}

/**
 * rewriteChanged 가 rewriteRun 직전에 저장소 밖에 떠 둔 파일과 범위다.
 * files 는 backend 아래 git 이 무시하지 않는 모든 파일이고, inScope 는 그 가운데 결과를 남길 Java 파일이다.
 */
class RewriteSnapshot(val dir: Path, val files: List<String>, val inScope: Set<String>)

var rewriteSnapshot: RewriteSnapshot? = null

fun gitLines(vararg args: String): List<String> {
    val result = providers.exec {
        workingDir = projectDir
        commandLine("git", "-c", "core.quotePath=false", *args)
        isIgnoreExitValue = true
    }
    if (result.result.get().exitValue != 0) {
        throw GradleException(
            "git ${args.joinToString(" ")} 이 실패했다. `git fetch origin` 뒤에 다시 돌린다.\n" +
                result.standardError.asText.get()
        )
    }
    return result.standardOutput.asText.get().lines().filter { it.isNotBlank() }
}

val rewriteRunTask = tasks.named("rewriteRun")

/**
 * rewriteRun 이 끝나면 범위 밖 파일을 실행 전 내용으로 되돌린다.
 * rewriteRun 이 실패했으면 범위 안 파일까지 모두 실행 전 내용으로 되돌린다.
 * 실패해도 돌도록 rewriteRun 의 finalizer 로 둔다.
 */
val rewriteChangedRestore = tasks.register("rewriteChangedRestore") {
    description = "rewriteChanged 가 범위 밖 파일을 실행 전 내용으로 되돌린다."
    onlyIf("rewriteChanged 가 실행 전 파일을 떠 두었다") { rewriteSnapshot != null }
    doLast {
        val snapshot = rewriteSnapshot!!
        val succeeded = rewriteRunTask.get().state.failure == null
        val targets = if (succeeded) snapshot.files.filterNot { it in snapshot.inScope } else snapshot.files
        val restored = mutableListOf<String>()
        try {
            targets.forEach { name ->
                val saved = snapshot.dir.resolve(name)
                val current = file(name).toPath()
                if (!Files.isRegularFile(current) || Files.mismatch(saved, current) != -1L) {
                    Files.createDirectories(current.parent)
                    Files.copy(saved, current, StandardCopyOption.REPLACE_EXISTING)
                    restored += name
                }
            }
        } catch (e: Exception) {
            throw GradleException("rewriteChanged: 파일을 되돌리지 못했다. 실행 전 내용이 ${snapshot.dir} 에 남아 있다.", e)
        }
        val fixed = if (succeeded) {
            snapshot.inScope.filter { Files.mismatch(snapshot.dir.resolve(it), file(it).toPath()) != -1L }
        } else {
            emptyList()
        }
        snapshot.dir.toFile().deleteRecursively()
        rewriteSnapshot = null
        if (!succeeded) {
            logger.error("rewriteChanged: rewriteRun 이 실패해 파일 ${restored.size}개를 실행 전 내용으로 되돌렸다.")
            return@doLast
        }
        logger.lifecycle("rewriteChanged: 범위 밖 파일 ${restored.size}개를 실행 전 내용으로 되돌렸다.")
        logger.lifecycle("rewriteChanged: 범위 안에서 고친 파일 ${fixed.size}개.")
        fixed.forEach { logger.lifecycle("  $it") }
    }
}

/**
 * rewriteChanged 로 부를 때만 rewriteRun 이 파일을 바꾸기 직전에 범위를 정하고 파일을 떠 둔다.
 * 범위는 HEAD 와 origin/main 의 공통 조상에서 작업 트리까지 바뀐 Java 파일과 추적하지 않는 새 Java 파일이다.
 * 작업 트리와 비교하므로 커밋하지 않은 편집이 있는 파일도 범위에 든다.
 * 범위 안 파일이 없으면 rewriteRun 의 나머지 동작을 건너뛴다.
 */
rewriteRunTask {
    finalizedBy(rewriteChangedRestore)
    doFirst {
        if (!gradle.taskGraph.hasTask(":rewriteChanged")) {
            return@doFirst
        }
        val base = gitLines("merge-base", "HEAD", "origin/main").single()
        val changed = gitLines("diff", "--name-only", "--relative", base) +
            gitLines("ls-files", "--others", "--exclude-standard")
        val inScope = changed.filter { it.endsWith(".java") && file(it).isFile }.toSortedSet()
        if (inScope.isEmpty()) {
            logger.lifecycle("rewriteChanged: 공통 조상 뒤에 바뀐 Java 파일이 없어 레시피를 돌리지 않는다.")
            throw StopExecutionException()
        }
        val files = gitLines("ls-files", "--cached", "--others", "--exclude-standard").filter { file(it).isFile }
        val dir = Files.createTempDirectory("rewrite-changed-")
        files.forEach { name ->
            val saved = dir.resolve(name)
            Files.createDirectories(saved.parent)
            Files.copy(file(name).toPath(), saved)
        }
        rewriteSnapshot = RewriteSnapshot(dir, files, inScope)
        logger.lifecycle("rewriteChanged: 범위 안 Java 파일 ${inScope.size}개. 실행 전 파일을 $dir 에 떠 두었다.")
    }
}

tasks.register("rewriteChanged") {
    group = "rewrite"
    description = "origin/main 과의 공통 조상 뒤에 바뀐 Java 파일에만 OpenRewrite 레시피 결과를 남긴다."
    // rewriteChangedRestore 는 rewriteRun 의 finalizer 로만 둔다. dependsOn 에 넣으면 rewriteRun 이 실패할 때 함께 취소된다.
    dependsOn(rewriteRunTask)
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
