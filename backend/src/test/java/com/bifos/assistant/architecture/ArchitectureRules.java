package com.bifos.assistant.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.bifos.assistant.browser.infra.BrowserProperties;
import com.bifos.assistant.chat.application.DelegationWakeProperties;
import com.bifos.assistant.chat.application.ModelTierProperties;
import com.bifos.assistant.chat.application.StarterProperties;
import com.bifos.assistant.connector.application.ConnectorPolicyProperties;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.memory.application.MemoryEncryptionProperties;
import com.bifos.assistant.memory.application.MemoryProposalProperties;
import com.bifos.assistant.orchestration.application.DelegationProperties;
import com.bifos.assistant.proactive.application.AutonomyProperties;
import com.bifos.assistant.proactive.application.ProactiveCheckProperties;
import com.bifos.assistant.proactive.application.ProactiveLoopProperties;
import com.bifos.assistant.usage.application.UserExecutionProperties;
import com.bifos.assistant.usage.infra.PricingProperties;
import com.bifos.assistant.workspace.infra.WorkspaceProperties;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import java.lang.annotation.Annotation;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.TestPropertySources;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoBeans;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBeans;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * backend 의 구조 규칙이다. 문서는 규칙을 이 클래스의 상수 이름으로 가리킨다.
 *
 * <p>규칙의 {@code as(...)} 설명이 기준 파일의 열쇠다. 설명을 바꾸면 그 규칙을 다시 얼린다.
 * 기준 파일을 갱신하는 방법은 {@code backend/docs/code-architecture.md} 의 「구조 규칙의 기준 파일」 절에 있다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ArchitectureRules {

    /** Spring 의 {@code Transactional} 을 import 하므로 Jakarta 쪽은 이 상수에서만 전체 이름으로 쓴다. */
    private static final Class<? extends Annotation> JAKARTA_TRANSACTIONAL =
            jakarta.transaction.Transactional.class; // 전체 이름 허용: Spring 과 Jakarta 의 Transactional 이름이 같다

    /**
     * 최상위 패키지 사이의 간선이 순환에 속하지 않는다. {@code shared} 는 그래프에서 뺀다.
     *
     * <p>근거: {@code backend/docs/code-architecture.md} 「backend 패키지」 의 {@code mcp} 와 {@code orchestration} 문단.
     */
    public static final ArchRule TOP_LEVEL_PACKAGES_FREE_OF_CYCLES = classes()
            .that()
            .resideInAPackage("com.bifos.assistant..")
            .should(new TopLevelPackageCycles())
            .as("최상위 패키지 사이의 간선은 순환에 속하지 않는다");

    /**
     * 최상위 패키지는 층 순서에서 자기보다 아래에 있는 패키지만 쓴다. 순서에 없는 최상위 패키지도 위반이다.
     * {@code shared} 는 그래프에서 뺀다. 순서는 {@code TopLevelPackageOrder.ORDER} 가 갖는다.
     *
     * <p>근거: {@code backend/docs/code-architecture.md} 「최상위 패키지의 층 순서」, ADR-068.
     */
    public static final ArchRule TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER = classes()
            .that()
            .resideInAPackage("com.bifos.assistant..")
            .should(new TopLevelPackageOrder())
            .as("최상위 패키지는 층 순서의 아래쪽만 쓴다");

    /**
     * {@code shared} 는 다른 최상위 패키지에 의존하지 않는다.
     *
     * <p>근거: {@code backend/docs/code-architecture.md} 「backend 패키지」 의 {@code shared/auth}, {@code shared/error} 책임.
     */
    public static final ArchRule SHARED_DOES_NOT_DEPEND_ON_DOMAINS = noClasses()
            .that()
            .resideInAPackage("com.bifos.assistant.shared..")
            .should()
            .dependOnClassesThat(resideInAPackage("com.bifos.assistant.(*)..")
                    .and(resideOutsideOfPackage("com.bifos.assistant.shared..")))
            .as("shared 는 다른 최상위 패키지에 의존하지 않는다");

    /**
     * 도메인 안은 {@code presentation} 에서 {@code application} 을 거쳐 {@code infra} 와 {@code domain} 으로 흐른다.
     * 아래 층이 위 층을 쓰는 것과 {@code presentation} 이 {@code infra} 를 바로 쓰는 것을 막는다.
     * 컨트롤러가 저장소를 바로 쓰면 권한 확인과 트랜잭션 경계를 서비스가 갖지 못한다.
     *
     * <p>근거: {@code backend/docs/code-architecture.md} 「backend 패키지」.
     */
    public static final ArchRule LAYER_DIRECTION = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("presentation")
            .definedBy("com.bifos.assistant.*.presentation..")
            .layer("application")
            .definedBy("com.bifos.assistant.*.application..")
            .layer("infra")
            .definedBy("com.bifos.assistant.*.infra..")
            .layer("domain")
            .definedBy("com.bifos.assistant.*.domain..")
            .whereLayer("presentation")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("application")
            .mayOnlyBeAccessedByLayers("presentation")
            .whereLayer("infra")
            .mayOnlyBeAccessedByLayers("application")
            .whereLayer("domain")
            .mayOnlyBeAccessedByLayers("presentation", "application", "infra")
            .as("층은 presentation 에서 application 을 거쳐 infra 와 domain 으로 흐른다");

    /**
     * {@code domain} 은 웹 계층의 타입에 의존하지 않는다.
     * Spring Web, HTTP, Servlet 타입과 {@code presentation} 을 쓰지 않는다.
     *
     * <p>근거: {@code backend/docs/code-architecture.md} 「backend 패키지」.
     */
    public static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_WEB = noClasses()
            .that()
            .resideInAPackage("com.bifos.assistant.*.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..",
                    "org.springframework.http..",
                    "jakarta.servlet..",
                    "com.bifos.assistant.*.presentation..")
            .as("domain 은 웹 계층에 의존하지 않는다");

    /**
     * {@code orchestration} 은 {@code mcp} 에 의존하지 않는다.
     *
     * <p>근거: {@code backend/docs/code-architecture.md} 「backend 패키지」 의
     * 「{@code mcp} 는 {@code orchestration} 을 부르고, {@code orchestration} 은 {@code mcp} 를 import 하지 않는다」.
     */
    public static final ArchRule ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP = noClasses()
            .that()
            .resideInAPackage("com.bifos.assistant.orchestration..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bifos.assistant.mcp..")
            .as("orchestration 은 mcp 에 의존하지 않는다");

    /**
     * {@code mcp} 는 Hermes 를 부르는 타입에 의존하지 않는다.
     * 이름이 {@code Client} 로 끝나는 타입과 {@code HermesRunEventStream}, {@code HermesProfileKeyStore} 가 대상이다.
     * {@code hermes.HermesProfileName} 같은 이름 규칙 값은 허용한다.
     *
     * <p>근거: {@code docs/features/agent-skill.md} 「다른 에이전트에게 맡기기」 의 「MCP 쪽은 Hermes 를 부르지 않는다」.
     */
    public static final ArchRule MCP_DOES_NOT_CALL_HERMES = noClasses()
            .that()
            .resideInAPackage("com.bifos.assistant.mcp..")
            .should()
            .dependOnClassesThat(resideInAPackage("com.bifos.assistant.hermes..")
                    .and(simpleNameEndingWith("Client")
                            .or(simpleName("HermesRunEventStream"))
                            .or(simpleName("HermesProfileKeyStore"))))
            .as("mcp 는 Hermes 를 부르는 타입에 의존하지 않는다");

    /**
     * {@code orchestration} 은 {@code ChatService} 에 의존하지 않는다.
     *
     * <p>근거: {@code docs/features/chat.md} 「중지」 의 「{@code ChatService} 와 흐름이 서로를 부르지 않게」.
     */
    public static final ArchRule ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE = noClasses()
            .that()
            .resideInAPackage("com.bifos.assistant.orchestration..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.bifos.assistant.chat.application.ChatService")
            .as("orchestration 은 ChatService 에 의존하지 않는다");

    /**
     * {@code hermes} 는 {@code people} 에 의존하지 않는다.
     *
     * <p>근거: {@code docs/features/users.md} 「사용자를 더할 때」 의
     * 「{@code hermes} 는 부르는 방법만 알고 순서를 모른다」.
     */
    public static final ArchRule HERMES_DOES_NOT_DEPEND_ON_PEOPLE = noClasses()
            .that()
            .resideInAPackage("com.bifos.assistant.hermes..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bifos.assistant.people..")
            .as("hermes 는 people 에 의존하지 않는다");

    /**
     * Jackson 2 의 {@code core} 와 {@code databind} 를 쓰지 않는다.
     * {@code com.fasterxml.jackson.annotation} 은 Jackson 3 도 쓰므로 허용한다.
     *
     * <p>근거: {@code backend/AGENTS.md} 「기술 주의점」 의 「Spring Boot 4 는 Jackson 3 을 쓴다」.
     */
    public static final ArchRule NO_JACKSON_2_DATABIND = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.fasterxml.jackson.core..", "com.fasterxml.jackson.databind..")
            .as("Jackson 2 의 core 와 databind 에 의존하지 않는다");

    /**
     * 컨트롤러 안에 record 를 두지 않는다.
     *
     * <p>근거: {@code backend/AGENTS.md} 「데이터 클래스는 컨트롤러 안에 두지 않는다」.
     */
    public static final ArchRule CONTROLLERS_HAVE_NO_NESTED_RECORDS = classes()
            .that()
            .areRecords()
            .and()
            .areNestedClasses()
            .should(notEnclosedByController())
            .allowEmptyShould(true)
            .as("컨트롤러 안에 record 를 두지 않는다");

    /**
     * {@code @Test} 와 {@code @ParameterizedTest} 메서드에는 {@code @DisplayName} 이 붙는다.
     * 메서드 이름은 영문 camelCase 이고, 테스트 보고서에 보이는 한국어 문장은 {@code @DisplayName} 이 갖는다.
     * 테스트 클래스만 읽는다.
     *
     * <p>근거: {@code backend/AGENTS.md} 「테스트」 의 「테스트 이름」.
     */
    public static final ArchRule TEST_METHODS_HAVE_DISPLAY_NAME = methods()
            .that()
            .areAnnotatedWith(Test.class)
            .or()
            .areAnnotatedWith(ParameterizedTest.class)
            .should()
            .beAnnotatedWith(DisplayName.class)
            .as("테스트 메서드에는 DisplayName 이 붙는다");

    /**
     * 검사 클래스나 그 중첩 클래스에 붙으면 Spring 이 컨텍스트를 따로 띄우는 주석의 전체 이름이다.
     *
     * <p>슬라이스 주석과 {@code AutoConfigureMockMvc} 는 Boot 4 에서 기능마다 따로 나뉜 모듈에 있고, 이 검사의 클래스패스에 그 모듈이 없다.
     * 클래스를 import 할 수 없어 이름 문자열로 적는다. ArchUnit 은 읽은 바이트코드의 주석 이름으로 견주므로 클래스가 없어도 찾는다.
     */
    private static final List<String> CONTEXT_SPLITTING_CLASS_ANNOTATIONS = List.of(
            MockitoBean.class.getName(),
            MockitoBeans.class.getName(),
            MockitoSpyBean.class.getName(),
            MockitoSpyBeans.class.getName(),
            Import.class.getName(),
            ImportAutoConfiguration.class.getName(),
            TestPropertySource.class.getName(),
            TestPropertySources.class.getName(),
            SpringBootTest.class.getName(),
            ContextConfiguration.class.getName(),
            ActiveProfiles.class.getName(),
            DirtiesContext.class.getName(),
            TestConfiguration.class.getName(),
            TestExecutionListeners.class.getName(),
            BootstrapWith.class.getName(),
            JsonTest.class.getName(),
            "org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest",
            "org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc",
            "org.springframework.boot.webflux.test.autoconfigure.WebFluxTest",
            "org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest",
            "org.springframework.boot.jdbc.test.autoconfigure.JdbcTest",
            "org.springframework.boot.restclient.test.autoconfigure.RestClientTest");

    /** 필드에 붙으면 컨텍스트를 나누는 주석의 전체 이름이다. */
    private static final List<String> CONTEXT_SPLITTING_FIELD_ANNOTATIONS =
            List.of(MockitoBean.class.getName(), MockitoSpyBean.class.getName(), TestBean.class.getName());

    /** 메서드에 붙으면 컨텍스트를 나누거나 닫는 주석의 전체 이름이다. */
    private static final List<String> CONTEXT_SPLITTING_METHOD_ANNOTATIONS =
            List.of(DynamicPropertySource.class.getName(), DirtiesContext.class.getName());

    /**
     * {@code testsupport} 밖의 검사 클래스는 Spring 컨텍스트를 나누는 선언을 두지 않는다. 대상 주석은 클래스와 중첩 클래스는
     * {@link #CONTEXT_SPLITTING_CLASS_ANNOTATIONS}, 필드는 {@link #CONTEXT_SPLITTING_FIELD_ANNOTATIONS}, 메서드는
     * {@link #CONTEXT_SPLITTING_METHOD_ANNOTATIONS} 가 갖는다. 중첩 {@code TestConfiguration} 클래스는 따로 가져오지 않아도 Spring
     * Boot 가 찾아 컨텍스트를 나눈다. 테스트 클래스만 읽는다. MySQL 검사({@code @Tag("mysql")} 이 붙었거나 그것을 상속한 클래스와 그 중첩 클래스)는 따로 띄우므로 뺀다.
     * 이름은 보지 않는다. 이름에 {@code Mysql} 을 넣는 것만으로 이 규칙을 피하지 못하게 한다.
     *
     * <p>근거: ADR-20261007 / test-context-base. 검사 클래스마다 선언이 다르면 컨텍스트가 늘고, 닫힌 컨텍스트가 힙을 붙잡는다.
     */
    public static final ArchRule TESTS_DO_NOT_SPLIT_CONTEXT = classes()
            .that(resideOutsideOfPackage("com.bifos.assistant.testsupport.."))
            .and(notMysqlTest())
            .should(notDeclareContextSplitting())
            .allowEmptyShould(true)
            .as("검사 클래스는 Spring 컨텍스트를 나누는 선언을 두지 않는다");

    /**
     * {@code application} 밖의 클래스와 메서드에는 {@code @Transactional} 을 붙이지 않는다.
     * Spring 과 Jakarta 의 두 {@code @Transactional} 을 모두 막는다.
     *
     * <p>근거: 트랜잭션 경계는 유스케이스를 아는 층이 정한다. 컨트롤러와 저장소에 두면 경계가 둘로 갈린다.
     */
    public static final ArchRule TRANSACTIONAL_ONLY_IN_APPLICATION = CompositeArchRule.of(noClasses()
                    .that()
                    .resideOutsideOfPackage("..application..")
                    .should()
                    .beAnnotatedWith(Transactional.class))
            .and(noClasses()
                    .that()
                    .resideOutsideOfPackage("..application..")
                    .should()
                    .beAnnotatedWith(JAKARTA_TRANSACTIONAL))
            .and(noMethods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideOutsideOfPackage("..application..")
                    .should()
                    .beAnnotatedWith(Transactional.class))
            .and(noMethods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideOutsideOfPackage("..application..")
                    .should()
                    .beAnnotatedWith(JAKARTA_TRANSACTIONAL))
            .as("Transactional 은 application 안에서만 쓴다");

    /**
     * {@code Instant.now()} 를 직접 부르지 않는다.
     * {@code Clock} 을 받는 {@code Instant.now(Clock)} 은 허용한다.
     *
     * <p>근거: 시각을 주입받아야 테스트가 시각을 고정한다.
     */
    public static final ArchRule NO_DIRECT_INSTANT_NOW =
            noClasses().should().callMethod(Instant.class, "now").as("Instant.now() 를 직접 부르지 않는다");

    /**
     * {@code MessageDigest.getInstance} 는 {@code shared.util.Sha256} 만 부른다.
     *
     * <p>근거: 해시 구현을 한 곳에 둔다.
     */
    public static final ArchRule MESSAGE_DIGEST_ONLY_IN_SHA256 = noClasses()
            .that()
            .doNotHaveFullyQualifiedName("com.bifos.assistant.shared.util.Sha256")
            .should()
            .callMethod(MessageDigest.class, "getInstance", String.class)
            .as("MessageDigest.getInstance 는 Sha256 만 부른다");

    /**
     * 가상 스레드를 직접 띄우는 {@code Thread.ofVirtual()} 과 {@code Thread.startVirtualThread} 는
     * {@code shared.concurrent.VirtualThreadBackgroundTasks} 와 HTTP 연결에 묶인 {@code ChatEventStreams},
     * {@code NotificationEventStreams} 만 부른다. 요청 밖 작업은 {@code BackgroundTasks} 로 띄운다.
     *
     * <p>근거: ADR-20261007 / background-tasks. 직접 띄운 스레드는 검사가 끝날 때 기다리지 못한다.
     */
    public static final ArchRule VIRTUAL_THREADS_ONLY_THROUGH_BACKGROUND_TASKS = CompositeArchRule.of(noClasses()
                    .that()
                    .doNotHaveFullyQualifiedName("com.bifos.assistant.shared.concurrent.VirtualThreadBackgroundTasks")
                    .and()
                    .doNotHaveFullyQualifiedName("com.bifos.assistant.chat.presentation.ChatEventStreams")
                    .and()
                    .doNotHaveFullyQualifiedName(
                            "com.bifos.assistant.notification.presentation.NotificationEventStreams")
                    .should()
                    .callMethod(Thread.class, "ofVirtual"))
            .and(noClasses()
                    .that()
                    .doNotHaveFullyQualifiedName("com.bifos.assistant.shared.concurrent.VirtualThreadBackgroundTasks")
                    .and()
                    .doNotHaveFullyQualifiedName("com.bifos.assistant.chat.presentation.ChatEventStreams")
                    .and()
                    .doNotHaveFullyQualifiedName(
                            "com.bifos.assistant.notification.presentation.NotificationEventStreams")
                    .should()
                    .callMethod(Thread.class, "startVirtualThread", Runnable.class))
            .as("가상 스레드는 BackgroundTasks 와 SSE 연결만 직접 띄운다");

    /**
     * {@code Executors.newVirtualThreadPerTaskExecutor()} 는 {@code ResearchAndBuildFlow} 만 부른다.
     * 그 흐름은 {@code try} 블록이 닫힐 때 실행기가 작업을 모두 기다린다.
     *
     * <p>근거: ADR-20261007 / background-tasks. 블록 밖으로 살아 남는 실행기는 검사가 끝날 때 기다리지 못한다.
     */
    public static final ArchRule VIRTUAL_THREAD_EXECUTOR_ONLY_IN_RESEARCH_AND_BUILD_FLOW = noClasses()
            .that()
            .doNotHaveFullyQualifiedName("com.bifos.assistant.orchestration.application.ResearchAndBuildFlow")
            .should()
            .callMethod(Executors.class, "newVirtualThreadPerTaskExecutor")
            .as("Executors.newVirtualThreadPerTaskExecutor 는 ResearchAndBuildFlow 만 부른다");

    /**
     * {@code @ConfigurationProperties} 클래스에는 {@code @Validated} 도 붙는다.
     *
     * <p>근거: 잘못된 설정은 기동에서 멈춘다.
     */
    public static final ArchRule CONFIGURATION_PROPERTIES_ARE_VALIDATED = classes()
            .that()
            .areAnnotatedWith(ConfigurationProperties.class)
            .should()
            .beAnnotatedWith(Validated.class)
            .allowEmptyShould(true)
            .as("ConfigurationProperties 클래스에는 Validated 가 붙는다");

    /**
     * 서비스({@code @Service}, {@code @Component}, {@code @Repository})와 {@code infra} 안의 중첩 타입은
     * {@code private} 이다. 익명 클래스와 지역 클래스는 대상이 아니다.
     * 서비스가 돌려주는 모델은 서비스 파일 밖으로 뺀다. 캐시 키 같은 구현 세부는 {@code private} 으로 둔다.
     *
     * <p>근거: 서비스가 돌려주는 모델을 서비스 안에 두면 그 모델을 쓰는 쪽이 서비스를 import 하게 된다.
     */
    public static final ArchRule SERVICES_DO_NOT_EXPOSE_NESTED_TYPES = classes()
            .that()
            .areMemberClasses()
            .and(enclosedByServiceOrInfra())
            .should()
            .bePrivate()
            .allowEmptyShould(true)
            .as("서비스와 infra 안의 중첩 타입은 private 이다");

    /**
     * 엔티티의 {@code @Enumerated} 필드 타입은 {@code ..domain.type..} 에 둔다.
     * 저장되지 않는 서비스 결과와 화면용 enum 은 {@code <기능>.application.model} 에 두고,
     * {@code ErrorCode} 는 {@code shared.error} 에 둔다. 이 둘은 규칙으로 검사하지 않는다.
     *
     * <p>근거: 저장되는 값은 바꾸면 마이그레이션을 판단해야 하므로 한곳에 모아 보이게 한다.
     */
    public static final ArchRule ENUMERATED_FIELDS_USE_DOMAIN_TYPE = fields().that()
            .areAnnotatedWith(Enumerated.class)
            .and()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(Entity.class)
            .should()
            .haveRawType(resideInAPackage("..domain.type.."))
            .allowEmptyShould(true)
            .as("Enumerated 필드의 타입은 domain.type 에 있다");

    /**
     * {@code ..domain.type..} 의 클래스는 {@code application}, {@code infra}, {@code presentation} 에 의존하지 않는다.
     *
     * <p>근거: 저장되는 enum 은 가장 아래 층이다.
     */
    public static final ArchRule DOMAIN_TYPE_DEPENDS_ON_NOTHING_ABOVE = noClasses()
            .that()
            .resideInAPackage("..domain.type..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application..", "..infra..", "..presentation..")
            .allowEmptyShould(true)
            .as("domain.type 은 위 층에 의존하지 않는다");

    /** {@code LivePropertiesConfig} 의 전체 이름이다. 패키지 루트에 있어 구조 규칙이 이름으로 가리킨다. */
    private static final String LIVE_PROPERTIES_CONFIG = "com.bifos.assistant.LivePropertiesConfig";

    /** 기동 때 살펴보기 {@code max-duration} 과 {@code hermes.run-timeout} 을 견주는 설정 클래스다. */
    private static final String RUN_TIMEOUT_CHECK =
            "com.bifos.assistant.proactive.application.ProactiveCheckProperties$RunTimeoutCheck";

    /** 실행 중에 쓰는 설정 record 다. {@code HermesProperties} 는 두 칸만 해당하므로 따로 본다. */
    private static final List<Class<?>> LIVE_SETTINGS = List.of(
            DelegationWakeProperties.class,
            UserExecutionProperties.class,
            ProactiveCheckProperties.class,
            StarterProperties.class,
            DelegationProperties.class,
            AutonomyProperties.class,
            ProactiveLoopProperties.class,
            PricingProperties.class,
            MemoryEncryptionProperties.class,
            ModelTierProperties.class,
            ConnectorPolicyProperties.class,
            MemoryProposalProperties.class,
            BrowserProperties.class,
            WorkspaceProperties.class);

    /** {@code HermesProperties} 가운데 {@code LiveProperties} 로 읽는 칸이다. */
    private static final Set<String> HERMES_LIVE_ACCESSORS = Set.of("runTimeout", "pollInterval");

    /**
     * 실행 중에 쓰는 설정 record 를 생성자 인자나 필드로 갖는 운영 클래스는 {@code LivePropertiesConfig} 뿐이다.
     * 다른 클래스는 {@code LiveProperties<그 record>} 를 주입받아 쓸 때마다 {@code current()} 를 읽는다.
     * 예외는 기동 때 두 설정을 견주는 {@code ProactiveCheckProperties$RunTimeoutCheck} 다.
     *
     * <p>근거: ADR-20261007 / live-properties. record 를 필드로 쥐면 검사가 바꾼 값을 보지 못한다.
     */
    public static final ArchRule LIVE_SETTINGS_ONLY_THROUGH_LIVE_PROPERTIES = classes()
            .that()
            .doNotHaveFullyQualifiedName(LIVE_PROPERTIES_CONFIG)
            .and()
            .doNotHaveFullyQualifiedName(RUN_TIMEOUT_CHECK)
            .should(notHoldAny(LIVE_SETTINGS))
            .as("실행 중에 쓰는 설정 record 는 LivePropertiesConfig 만 주입받는다");

    /**
     * {@code HermesProperties} 를 생성자 인자나 필드로 갖고 {@code runTimeout()} 이나 {@code pollInterval()} 을 부르는 운영 클래스는
     * 실제 Hermes 클라이언트 {@code HermesRunEventStream}, {@code HttpHermesRunsClient} 와 기동 검사
     * {@code ProactiveCheckProperties$RunTimeoutCheck} 뿐이다. 다른 사용처는 {@code LiveProperties<HermesProperties>} 로 읽는다.
     *
     * <p>근거: ADR-20261007 / live-properties. 두 클라이언트는 HTTP 클라이언트를 만들 때 한 번 읽고, 통합 검사가 대역으로 바꾼다.
     */
    public static final ArchRule HERMES_TIMEOUTS_ONLY_THROUGH_LIVE_PROPERTIES = classes()
            .that()
            .doNotHaveFullyQualifiedName("com.bifos.assistant.hermes.HermesRunEventStream")
            .and()
            .doNotHaveFullyQualifiedName("com.bifos.assistant.hermes.HttpHermesRunsClient")
            .and()
            .doNotHaveFullyQualifiedName(RUN_TIMEOUT_CHECK)
            .should(notReadHermesTimeoutsFromInjectedProperties())
            .as("hermes 의 runTimeout 과 pollInterval 은 LiveProperties 로 읽는다");

    private static DescribedPredicate<JavaClass> enclosedByServiceOrInfra() {
        return new DescribedPredicate<>("바깥 클래스가 서비스이거나 infra 안에 있다") {
            @Override
            public boolean test(JavaClass item) {
                return item.getEnclosingClass()
                        .map(outer -> outer.isAnnotatedWith(Service.class)
                                || outer.isAnnotatedWith(Component.class)
                                || outer.isAnnotatedWith(Repository.class)
                                || resideInAPackage("..infra..").test(outer))
                        .orElse(false);
            }
        };
    }

    /** 생성자 인자나 필드의 타입이 {@code types} 가운데 하나면 위반이다. */
    private static ArchCondition<JavaClass> notHoldAny(List<Class<?>> types) {
        return new ArchCondition<>("실행 중에 쓰는 설정 record 를 생성자 인자나 필드로 갖지 않는다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Class<?> type : types) {
                    if (holds(item, type)) {
                        events.add(SimpleConditionEvent.violated(
                                item, item.getName() + " 가 " + type.getSimpleName() + " 을 생성자 인자나 필드로 갖는다"));
                    }
                }
            }
        };
    }

    /** {@code HermesProperties} 를 쥐고 {@code runTimeout()} 이나 {@code pollInterval()} 을 부르면 위반이다. */
    private static ArchCondition<JavaClass> notReadHermesTimeoutsFromInjectedProperties() {
        return new ArchCondition<>("HermesProperties 를 쥐고 runTimeout 이나 pollInterval 을 부르지 않는다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean reads = item.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTargetOwner().isEquivalentTo(HermesProperties.class)
                                && HERMES_LIVE_ACCESSORS.contains(call.getName()));
                if (reads && holds(item, HermesProperties.class)) {
                    events.add(SimpleConditionEvent.violated(
                            item, item.getName() + " 가 HermesProperties 를 쥐고 runTimeout 이나 pollInterval 을 부른다"));
                }
            }
        };
    }

    /** 그 클래스가 {@code type} 을 필드로 갖거나 생성자 인자로 받는지 본다. */
    private static boolean holds(JavaClass item, Class<?> type) {
        for (JavaField field : item.getFields()) {
            if (field.getRawType().isEquivalentTo(type)) {
                return true;
            }
        }
        for (JavaConstructor constructor : item.getConstructors()) {
            if (constructor.getRawParameterTypes().stream().anyMatch(each -> each.isEquivalentTo(type))) {
                return true;
            }
        }
        return false;
    }

    /** 그 클래스와 바깥 클래스, 그들의 상위 클래스 어디에도 {@code @Tag("mysql")} 이 없다. JUnit 의 {@code Tag} 는 상속된다. */
    private static DescribedPredicate<JavaClass> notMysqlTest() {
        return new DescribedPredicate<>("MySQL 검사가 아니다") {
            @Override
            public boolean test(JavaClass item) {
                Optional<JavaClass> current = Optional.of(item);
                while (current.isPresent()) {
                    JavaClass each = current.get();
                    if (taggedMysql(each)
                            || each.getAllRawSuperclasses().stream().anyMatch(ArchitectureRules::taggedMysql)) {
                        return false;
                    }
                    current = each.getEnclosingClass();
                }
                return true;
            }
        };
    }

    private static boolean taggedMysql(JavaClass item) {
        return item.tryGetAnnotationOfType(Tag.class)
                .map(tag -> "mysql".equals(tag.value()))
                .orElse(false);
    }

    private static ArchCondition<JavaClass> notDeclareContextSplitting() {
        return new ArchCondition<>("컨텍스트를 나누는 주석을 클래스, 필드, 메서드에 두지 않는다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (String type : CONTEXT_SPLITTING_CLASS_ANNOTATIONS) {
                    if (item.isAnnotatedWith(type)) {
                        events.add(SimpleConditionEvent.violated(
                                item, item.getName() + " 에 @" + simpleNameOf(type) + " 이 붙었다"));
                    }
                }
                for (JavaField field : item.getFields()) {
                    for (String type : CONTEXT_SPLITTING_FIELD_ANNOTATIONS) {
                        if (field.isAnnotatedWith(type)) {
                            events.add(SimpleConditionEvent.violated(
                                    field, field.getFullName() + " 에 @" + simpleNameOf(type) + " 이 붙었다"));
                        }
                    }
                }
                for (JavaMethod method : item.getMethods()) {
                    for (String type : CONTEXT_SPLITTING_METHOD_ANNOTATIONS) {
                        if (method.isAnnotatedWith(type)) {
                            events.add(SimpleConditionEvent.violated(
                                    method, method.getFullName() + " 에 @" + simpleNameOf(type) + " 이 붙었다"));
                        }
                    }
                }
            }
        };
    }

    private static String simpleNameOf(String annotationName) {
        return annotationName.substring(annotationName.lastIndexOf('.') + 1);
    }

    private static ArchCondition<JavaClass> notEnclosedByController() {
        return new ArchCondition<>("바깥 클래스의 단순 이름이 Controller 로 끝나지 않는다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaClass> enclosing = item.getEnclosingClass();
                while (enclosing.isPresent()) {
                    if (enclosing.get().getSimpleName().endsWith("Controller")) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                item.getName() + " 가 컨트롤러 " + enclosing.get().getName() + " 안에 있다"));
                        return;
                    }
                    enclosing = enclosing.get().getEnclosingClass();
                }
            }
        };
    }
}
