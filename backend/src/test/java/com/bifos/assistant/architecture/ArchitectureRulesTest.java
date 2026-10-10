package com.bifos.assistant.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import jakarta.persistence.Entity;
import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * {@link ArchitectureRules} 의 규칙을 운영 코드에 건다.
 * 지금 있는 위반은 {@code backend/config/archunit/store/} 의 기준 파일에 얼려 두고 새 위반만 실패시킨다.
 */
@Tag("architecture")
class ArchitectureRulesTest {

    static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.bifos.assistant");

    static final JavaClasses TESTS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
            .importPackages("com.bifos.assistant");

    @TempDir
    Path temporary;

    /** 소스와 클래스는 테스트 임시 경로에만 두며 이후 placement 검사도 이 빌더를 재사용한다. */
    static JavaClasses compileFixture(Path directory, Map<String, String> sources) throws IOException {
        Path sourceRoot = Files.createDirectories(directory.resolve("sources"));
        Path classesRoot = Files.createDirectories(directory.resolve("classes"));
        List<File> files = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = sourceRoot.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            files.add(file.toFile());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("fixture 컴파일은 JDK 를 요구한다").isNotNull();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<String> options =
                    List.of("-proc:none", "-classpath", fixtureClasspath(), "-d", classesRoot.toString());
            boolean compiled = compiler.getTask(
                            null, manager, diagnostics, options, null, manager.getJavaFileObjectsFromFiles(files))
                    .call();
            assertThat(compiled)
                    .as("fixture 컴파일 진단: %s", diagnostics.getDiagnostics())
                    .isTrue();
        }
        return new ClassFileImporter().importPath(classesRoot);
    }

    private static String fixtureClasspath() {
        Set<String> entries = new LinkedHashSet<>(
                List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
        for (ClassLoader loader = Thread.currentThread().getContextClassLoader();
                loader != null;
                loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    entries.add(new File(url.getPath()).toString());
                }
            }
        }
        for (Class<?> anchor : List.of(Service.class, Entity.class, RequestMapping.class)) {
            entries.add(new File(anchor.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .getPath())
                    .toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    @Test
    @DisplayName("기존 층 규칙은 목적별 하위 패키지의 정상 흐름도 허용한다")
    void acceptsLayerFixture() throws IOException {
        JavaClasses fixture = compileFixture(
                temporary,
                Map.of(
                        "com.bifos.assistant.fixture.admin.presentation.Controller", """
                        package com.bifos.assistant.fixture.admin.presentation;
                        import com.bifos.assistant.fixture.admin.application.UseCase;
                        public class Controller { UseCase service; }
                        """,
                        "com.bifos.assistant.fixture.admin.application.UseCase", """
                        package com.bifos.assistant.fixture.admin.application;
                        import com.bifos.assistant.fixture.infra.Store;
                        public class UseCase { Store store; }
                        """,
                        "com.bifos.assistant.fixture.infra.Store", """
                        package com.bifos.assistant.fixture.infra;
                        import com.bifos.assistant.fixture.domain.Policy;
                        public class Store { Policy policy; }
                        """,
                        "com.bifos.assistant.fixture.domain.Policy", """
                        package com.bifos.assistant.fixture.domain;
                        public class Policy {}
                        """));
        assertThat(fixture).hasSize(4);
        ArchitectureRules.LAYER_DIRECTION.check(fixture);
        ArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_WEB.check(fixture);
    }

    @Test
    @DisplayName("기존 층 규칙은 목적별 컨트롤러에서 infra 로 바로 가는 의존을 거절한다")
    void rejectsControllerInfraFixture() throws IOException {
        JavaClasses fixture = compileFixture(
                temporary,
                Map.of(
                        "com.bifos.assistant.fixture.admin.presentation.Controller", """
                        package com.bifos.assistant.fixture.admin.presentation;
                        import com.bifos.assistant.fixture.infra.Store;
                        public class Controller { Store store; }
                        """,
                        "com.bifos.assistant.fixture.infra.Store", """
                        package com.bifos.assistant.fixture.infra;
                        public class Store {}
                        """));
        assertThat(fixture).hasSize(2);
        assertThat(ArchitectureRules.LAYER_DIRECTION
                        .evaluate(fixture)
                        .getFailureReport()
                        .toString())
                .contains("fixture.admin.presentation.Controller", "fixture.infra.Store", "층은 presentation");
    }

    @Test
    @DisplayName("기존 domain 규칙은 presentation 과 Spring Web 의존을 거절한다")
    void rejectsDomainWebFixture() throws IOException {
        JavaClasses fixture = compileFixture(
                temporary,
                Map.of(
                        "com.bifos.assistant.fixture.admin.domain.Policy", """
                        package com.bifos.assistant.fixture.admin.domain;
                        import com.bifos.assistant.fixture.admin.presentation.Controller;
                        import org.springframework.http.HttpStatus;
                        public class Policy {
                            Controller controller;
                            HttpStatus status;
                        }
                        """,
                        "com.bifos.assistant.fixture.admin.presentation.Controller", """
                        package com.bifos.assistant.fixture.admin.presentation;
                        public class Controller {}
                        """));
        assertThat(fixture).hasSize(2);
        assertThat(ArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_WEB
                        .evaluate(fixture)
                        .getFailureReport()
                        .toString())
                .contains(
                        "fixture.admin.domain.Policy",
                        "fixture.admin.presentation.Controller",
                        "HttpStatus",
                        "domain 은 웹 계층");
        assertThat(ArchitectureRules.LAYER_DIRECTION.evaluate(fixture).hasViolation())
                .isTrue();
    }

    @Test
    @DisplayName("최상위 패키지 사이에 새 순환 간선이 생기지 않는다")
    void topLevelPackagesFreeOfCycles() {
        FreezingArchRule.freeze(ArchitectureRules.TOP_LEVEL_PACKAGES_FREE_OF_CYCLES)
                .check(MAIN);
    }

    @Test
    @DisplayName("최상위 패키지가 층 순서의 위쪽을 새로 쓰지 않는다")
    void topLevelPackagesFollowLayerOrder() {
        FreezingArchRule.freeze(ArchitectureRules.TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER)
                .check(MAIN);
    }

    @Test
    @DisplayName("shared 가 다른 최상위 패키지를 새로 쓰지 않는다")
    void sharedDoesNotDependOnDomains() {
        FreezingArchRule.freeze(ArchitectureRules.SHARED_DOES_NOT_DEPEND_ON_DOMAINS)
                .check(MAIN);
    }

    @Test
    @DisplayName("아래 층이 위 층을 새로 쓰지 않고 presentation 이 infra 를 새로 바로 쓰지 않는다")
    void layerDirection() {
        FreezingArchRule.freeze(ArchitectureRules.LAYER_DIRECTION).check(MAIN);
    }

    @Test
    @DisplayName("domain 이 웹 계층을 쓰지 않는다")
    void domainDoesNotDependOnWeb() {
        FreezingArchRule.freeze(ArchitectureRules.DOMAIN_DOES_NOT_DEPEND_ON_WEB).check(MAIN);
    }

    @Test
    @DisplayName("orchestration 이 mcp 를 쓰지 않는다")
    void orchestrationDoesNotDependOnMcp() {
        FreezingArchRule.freeze(ArchitectureRules.ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP)
                .check(MAIN);
    }

    @Test
    @DisplayName("mcp 가 Hermes 를 부르는 타입을 쓰지 않는다")
    void mcpDoesNotCallHermes() {
        FreezingArchRule.freeze(ArchitectureRules.MCP_DOES_NOT_CALL_HERMES).check(MAIN);
    }

    @Test
    @DisplayName("orchestration 이 ChatService 를 쓰지 않는다")
    void orchestrationDoesNotCallChatService() {
        FreezingArchRule.freeze(ArchitectureRules.ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE)
                .check(MAIN);
    }

    @Test
    @DisplayName("hermes 가 people 을 쓰지 않는다")
    void hermesDoesNotDependOnPeople() {
        FreezingArchRule.freeze(ArchitectureRules.HERMES_DOES_NOT_DEPEND_ON_PEOPLE)
                .check(MAIN);
    }

    @Test
    @DisplayName("Jackson 2 의 core 와 databind 를 쓰지 않는다")
    void noJackson2Databind() {
        FreezingArchRule.freeze(ArchitectureRules.NO_JACKSON_2_DATABIND).check(MAIN);
    }

    @Test
    @DisplayName("컨트롤러 안에 record 를 두지 않는다")
    void controllersHaveNoNestedRecords() {
        FreezingArchRule.freeze(ArchitectureRules.CONTROLLERS_HAVE_NO_NESTED_RECORDS)
                .check(MAIN);
    }

    @Test
    @DisplayName("Transactional 이 application 밖에 새로 붙지 않는다")
    void transactionalOnlyInApplication() {
        FreezingArchRule.freeze(ArchitectureRules.TRANSACTIONAL_ONLY_IN_APPLICATION)
                .check(MAIN);
    }

    @Test
    @DisplayName("Instant.now() 를 새로 직접 부르지 않는다")
    void noDirectInstantNow() {
        FreezingArchRule.freeze(ArchitectureRules.NO_DIRECT_INSTANT_NOW).check(MAIN);
    }

    @Test
    @DisplayName("MessageDigest.getInstance 를 Sha256 밖에서 새로 부르지 않는다")
    void messageDigestOnlyInSha256() {
        FreezingArchRule.freeze(ArchitectureRules.MESSAGE_DIGEST_ONLY_IN_SHA256).check(MAIN);
    }

    @Test
    @DisplayName("가상 스레드를 BackgroundTasks 와 SSE 연결 밖에서 새로 직접 띄우지 않는다")
    void virtualThreadsOnlyThroughBackgroundTasks() {
        FreezingArchRule.freeze(ArchitectureRules.VIRTUAL_THREADS_ONLY_THROUGH_BACKGROUND_TASKS)
                .check(MAIN);
    }

    @Test
    @DisplayName("Executors.newVirtualThreadPerTaskExecutor 를 ResearchAndBuildFlow 밖에서 새로 부르지 않는다")
    void virtualThreadExecutorOnlyInResearchAndBuildFlow() {
        FreezingArchRule.freeze(ArchitectureRules.VIRTUAL_THREAD_EXECUTOR_ONLY_IN_RESEARCH_AND_BUILD_FLOW)
                .check(MAIN);
    }

    @Test
    @DisplayName("ConfigurationProperties 클래스에 Validated 가 붙는다")
    void configurationPropertiesAreValidated() {
        FreezingArchRule.freeze(ArchitectureRules.CONFIGURATION_PROPERTIES_ARE_VALIDATED)
                .check(MAIN);
    }

    @Test
    @DisplayName("서비스와 infra 안에 공개된 중첩 타입이 새로 생기지 않는다")
    void servicesDoNotExposeNestedTypes() {
        FreezingArchRule.freeze(ArchitectureRules.SERVICES_DO_NOT_EXPOSE_NESTED_TYPES)
                .check(MAIN);
    }

    @Test
    @DisplayName("엔티티의 Enumerated 필드 타입이 domain.type 에 있다")
    void enumeratedFieldsUseDomainType() {
        FreezingArchRule.freeze(ArchitectureRules.ENUMERATED_FIELDS_USE_DOMAIN_TYPE)
                .check(MAIN);
    }

    @Test
    @DisplayName("domain.type 이 위 층을 쓰지 않는다")
    void domainTypeDependsOnNothingAbove() {
        FreezingArchRule.freeze(ArchitectureRules.DOMAIN_TYPE_DEPENDS_ON_NOTHING_ABOVE)
                .check(MAIN);
    }

    @Test
    @DisplayName("실행 중에 쓰는 설정 record 를 LivePropertiesConfig 밖에서 새로 주입받지 않는다")
    void liveSettingsOnlyThroughLiveProperties() {
        FreezingArchRule.freeze(ArchitectureRules.LIVE_SETTINGS_ONLY_THROUGH_LIVE_PROPERTIES)
                .check(MAIN);
    }

    @Test
    @DisplayName("hermes 의 runTimeout 과 pollInterval 을 LiveProperties 밖에서 새로 읽지 않는다")
    void hermesTimeoutsOnlyThroughLiveProperties() {
        FreezingArchRule.freeze(ArchitectureRules.HERMES_TIMEOUTS_ONLY_THROUGH_LIVE_PROPERTIES)
                .check(MAIN);
    }

    @Test
    @DisplayName("testsupport 밖의 검사 클래스가 Spring 컨텍스트를 나누는 선언을 두지 않는다")
    void testsDoNotSplitContext() {
        FreezingArchRule.freeze(ArchitectureRules.TESTS_DO_NOT_SPLIT_CONTEXT).check(TESTS);
    }

    @Test
    @DisplayName("테스트 메서드에 DisplayName 이 붙는다")
    void testMethodsHaveDisplayName() {
        FreezingArchRule.freeze(ArchitectureRules.TEST_METHODS_HAVE_DISPLAY_NAME)
                .check(TESTS);
    }
}
