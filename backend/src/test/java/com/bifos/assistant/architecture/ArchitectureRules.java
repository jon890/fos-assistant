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

import com.bifos.assistant.browser.config.BrowserProperties;
import com.bifos.assistant.chat.nextturn.config.DelegationWakeProperties;
import com.bifos.assistant.chat.starter.config.StarterProperties;
import com.bifos.assistant.connector.connection.config.ConnectorPolicyProperties;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.memory.config.MemoryEncryptionProperties;
import com.bifos.assistant.orchestration.config.DelegationProperties;
import com.bifos.assistant.proactive.autonomy.config.AutonomyProperties;
import com.bifos.assistant.proactive.check.config.ProactiveCheckProperties;
import com.bifos.assistant.proactive.loop.config.ProactiveLoopProperties;
import com.bifos.assistant.usage.execution.config.UserExecutionProperties;
import com.bifos.assistant.usage.pricing.config.PricingProperties;
import com.bifos.assistant.workspace.config.WorkspaceProperties;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.IdClass;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.lang.annotation.Annotation;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * backend 의 구조 규칙이다. 규범과 까닭은 docs/code-rules가, 실행 조건은 이 클래스가 갖는다.
 *
 * <p>규칙의 {@code as(...)} 설명이 기준 파일의 열쇠다. 설명을 바꾸면 그 규칙을 다시 얼린다.
 * 기준 파일을 갱신하는 방법은 {@code backend/docs/code-architecture.md} 의 「구조 규칙의 기준 파일」 절에 있다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ArchitectureRules {

    /** rule-1: 까닭과 예외는 docs/code-rules/rule-1.md를 본다. */
    public static final ArchRule AUDITING_LISTENERS_AND_CLOCK = JpaAuditingRules.rule();


    private static void require(JavaClass type, boolean valid, String detail, ConditionEvents events) {
        if (!valid) {
            events.add(SimpleConditionEvent.violated(type, type.getName() + ": " + detail));
        }
    }


    /** Spring 의 {@code Transactional} 을 import 하므로 Jakarta 쪽은 이 상수에서만 전체 이름으로 쓴다. */
    private static final Class<? extends Annotation> JAKARTA_TRANSACTIONAL = jakarta.transaction.Transactional.class; // 전체 이름 허용: Spring 과 Jakarta 의 Transactional 이름이 같다

    /** rule-2: 까닭과 예외는 docs/code-rules/rule-2.md를 본다. */
    public static final ArchRule TOP_LEVEL_PACKAGES_FREE_OF_CYCLES = classes()
        .that()
        .resideInAPackage("com.bifos.assistant..")
        .should(new TopLevelPackageCycles())
        .as("최상위 패키지 사이의 간선은 순환에 속하지 않는다");

    /** rule-3: 까닭과 예외는 docs/code-rules/rule-3.md를 본다. */
    public static final ArchRule TOP_LEVEL_PACKAGES_FOLLOW_LAYER_ORDER = classes()
        .that()
        .resideInAPackage("com.bifos.assistant..")
        .should(new TopLevelPackageOrder())
        .as("최상위 패키지는 층 순서의 아래쪽만 쓴다");

    /** rule-4: 까닭과 예외는 docs/code-rules/rule-4.md를 본다. */
    public static final ArchRule SHARED_DOES_NOT_DEPEND_ON_DOMAINS = noClasses()
        .that()
        .resideInAPackage("com.bifos.assistant.shared..")
        .should()
        .dependOnClassesThat(resideInAPackage("com.bifos.assistant.(*)..")
            .and(resideOutsideOfPackage("com.bifos.assistant.shared..")))
        .as("shared 는 다른 최상위 패키지에 의존하지 않는다");

    /** rule-5: 까닭과 예외는 docs/code-rules/rule-5.md를 본다. */
    public static final ArchRule LAYER_DIRECTION = PackageLayoutRules.layerDirection(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-6: 까닭과 예외는 docs/code-rules/rule-6.md를 본다. */
    public static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_WEB = noClasses()
        .that()
        .resideInAPackage("com.bifos.assistant..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.web..",
            "org.springframework.http..",
            "jakarta.servlet..",
            "com.bifos.assistant..presentation..")
        .as("domain 은 웹 계층에 의존하지 않는다");

    /** rule-7: 까닭과 예외는 docs/code-rules/rule-7.md를 본다. */
    public static final ArchRule AGENT_ADMIN_APPLICATION_PLACEMENT = agentAdminApplicationPlacement("com.bifos.assistant");

    /** rule-8: 까닭과 예외는 docs/code-rules/rule-8.md를 본다. */
    public static final ArchRule AGENT_ADMIN_HTTP_CONTRACT = agentAdminHttpContract("com.bifos.assistant");

    /** rule-9: 까닭과 예외는 docs/code-rules/rule-9.md를 본다. */
    public static final ArchRule AGENT_PURGE_CONTRACT_PLACEMENT = agentPurgeContractPlacement("com.bifos.assistant");


    /** 전체 운영 루트에서 소유 타입을 찾으므로 옛 위치·다른 기능·누락·중복도 실패한다. */
    static ArchRule agentPurgeContractPlacement(String root) {
        Map<String, String> owners = Map.of(
            "AgentPurger", "agent.application.AgentPurger",
            "AgentPurgeWriter", "agent.application.AgentPurgeWriter",
            "AgentPurgeParticipant", "agent.application.port.AgentPurgeParticipant",
            "AgentPurgeOutcome", "agent.application.dto.AgentPurgeOutcome");
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(ownedTypes(root, owners, true))
            .andShould(agentPurgeRoles(root))
            .as("AGENT_PURGE_CONTRACT_PLACEMENT: 정리 실행과 참여 계약의 소유·역할·방향을 지킨다");
    }


    private static ArchCondition<JavaClass> agentPurgeRoles(String root) {
        return new ArchCondition<>("purge 의 실행과 공개 결과 역할 및 참여 구현을 import 하지 않는 방향을 지킨다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.getEnclosingClass().isPresent()
                    || !Set.of("AgentPurger", "AgentPurgeWriter", "AgentPurgeParticipant", "AgentPurgeOutcome")
                        .contains(item.getSimpleName())) {
                    return;
                }
                if (Set.of("AgentPurger", "AgentPurgeWriter").contains(item.getSimpleName())) {
                    require(item, item.isAnnotatedWith(Component.class), "정리 실행은 Component 다", events);
                }
                if (item.getSimpleName().equals("AgentPurgeParticipant")) {
                    require(item, item.isInterface(), "참여 port 는 interface 다", events);
                }
                if (item.getSimpleName().equals("AgentPurgeOutcome")) {
                    require(item, item.isEnum(), "정리 결과는 enum 이다", events);
                }
                item.getDirectDependenciesFromSelf().forEach(dependency -> {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    if (targetPackage.startsWith(root + ".chat.")
                        || targetPackage.startsWith(root + ".connector.") || targetPackage.startsWith(root + ".proactive.")) {
                        events.add(SimpleConditionEvent.violated(item, dependency.getDescription() + " agent 는 참여 구현의 기능을 import 하지 않는다"));
                    }
                });
            }
        };
    }


    /** 정상·위반 fixture 도 운영 규칙과 같은 조건을 쓰며 바뀌는 것은 루트뿐이다. */
    static ArchRule agentAdminApplicationPlacement(String root) {
        Map<String, String> owners = Map.of(
            "AgentAdminService", "admin.agent.application.AgentAdminService",
            "AgentCreateCommand", "admin.agent.application.dto.AgentCreateCommand",
            "AgentUpdateCommand", "admin.agent.application.dto.AgentUpdateCommand");
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(ownedTypes(root, owners))
            .andShould(adminApplicationRoles(root))
            .as("AGENT_ADMIN_APPLICATION_PLACEMENT: 관리자 서비스와 명령의 소유 경계를 지킨다");
    }


    /** HTTP 타입을 기존 package 로 되돌리거나 중복·누락해도 전체 루트에서 찾는다. */
    static ArchRule agentAdminHttpContract(String root) {
        Map<String, String> owners = Map.of(
            "AgentAdminController", "admin.agent.presentation.AgentAdminController",
            "AgentAdminDtos", "admin.agent.presentation.AgentAdminDtos",
            "CreateAgentRequest", "admin.agent.presentation.AgentAdminDtos$CreateAgentRequest",
            "UpdateAgentRequest", "admin.agent.presentation.AgentAdminDtos$UpdateAgentRequest",
            "AdminAgentView", "admin.agent.presentation.AgentAdminDtos$AdminAgentView");
        return classes()
            .that()
            .resideInAPackage(root + "..")
            .should(ownedTypes(root, owners))
            .andShould(adminHttpRoles(root))
            .as("AGENT_ADMIN_HTTP_CONTRACT: 관리자 HTTP 배치를 지킨다");
    }


    /** 이름으로 전체 루트를 골라 누락과 중복을 막고, 위치는 FQN 으로 따로 판정한다. */
    static ArchCondition<JavaClass> ownedTypes(String root, Map<String, String> owners) {
        return ownedTypes(root, owners, false);
    }


    private static ArchCondition<JavaClass> ownedTypes(String root, Map<String, String> owners, boolean topLevelOnly) {
        return new ArchCondition<>("소유 타입이 기대한 FQN 에 각각 하나씩 있다") {
            private final Map<String, Integer> counts = new HashMap<>();


            @Override
            public void init(Collection<JavaClass> allObjectsToTest) {
                counts.clear();
            }


            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String key = item.getSimpleName();
                if (!owners.containsKey(key) || (topLevelOnly && item.getEnclosingClass().isPresent())) {
                    return;
                }
                counts.merge(key, 1, Integer::sum);
                String expected = root + "." + owners.get(key);
                require(item, item.getName().equals(expected), expected + " 에 있어야 한다", events);
            }


            @Override
            public void finish(ConditionEvents events) {
                owners.forEach((key, expected) -> {
                    int count = counts.getOrDefault(key, 0);
                    if (count != 1) {
                        String name = root + "." + expected;
                        events.add(SimpleConditionEvent.violated(name, name + " 의 대상 수가 " + count + " 이다. 기대값은 1이다"));
                    }
                });
            }
        };
    }


    private static ArchCondition<JavaClass> adminApplicationRoles(String root) {
        return new ArchCondition<>("admin 은 application 업무만 소유하고 domain 과 infra 가 admin 을 쓰지 않는다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String boundary = root + ".admin.agent.";
                if (item.getPackageName().startsWith(boundary)) {
                    require(
                        item,
                        !item.isAnnotatedWith(Entity.class)
                            && !item.isAnnotatedWith(Embeddable.class)
                            && !item.isAnnotatedWith(Repository.class)
                            && !item.getSimpleName().endsWith("Repository"),
                        "Entity, Embeddable, Repository 는 admin 밖에 있다",
                        events);
                    if (item.isAnnotatedWith(Service.class)) {
                        require(
                            item,
                            item.getPackageName().equals(boundary + "application"),
                            "Service 는 admin.application 에 있다",
                            events);
                    }
                    if (item.isRecord()
                        && item.getModifiers().contains(JavaModifier.PUBLIC)
                        && item.getPackageName().contains(".application")) {
                        require(
                            item,
                            item.getPackageName().equals(boundary + "application.dto"),
                            "공개 application record 는 application.dto 에 있다",
                            events);
                    }
                    if (item.isAnnotatedWith(ConfigurationProperties.class)) {
                        require(item, item.getPackageName().equals(boundary + "config"), "설정 선언은 config 에 있다", events);
                    }
                }
                if (item.getName().equals(boundary + "application.AgentAdminService")) {
                    require(item, item.isAnnotatedWith(Service.class), "AgentAdminService 는 Service 다", events);
                }
                if (Set.of("AgentCreateCommand", "AgentUpdateCommand").contains(item.getSimpleName())) {
                    require(
                        item,
                        item.isRecord() && item.getModifiers().contains(JavaModifier.PUBLIC),
                        "관리 명령은 공개 record 다",
                        events);
                }
                if (item.getPackageName().contains(".domain.")
                    || item.getPackageName().endsWith(".domain")
                    || item.getPackageName().contains(".infra.")
                    || item.getPackageName().endsWith(".infra")) {
                    item.getDirectDependenciesFromSelf().stream()
                        .filter(dependency -> dependency.getTargetClass().getName().startsWith(boundary))
                        .forEach(dependency -> events.add(SimpleConditionEvent.violated(
                            item, dependency.getDescription() + ": domain 과 infra 는 admin 을 쓰지 않는다")));
                }
            }
        };
    }


    private static ArchCondition<JavaClass> adminHttpRoles(String root) {
        return new ArchCondition<>("관리 HTTP record 와 컨트롤러가 presentation 에 있고 mapped 메서드가 셋이다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String presentation = root + ".admin.agent.presentation";
                if (item.getPackageName().startsWith(root + ".admin.agent.")
                    && item.isRecord()
                    && !item.isAnnotatedWith(ConfigurationProperties.class)
                    && !item.getPackageName().contains(".application.")) {
                    require(
                        item,
                        item.getPackageName().equals(presentation)
                            && item.getEnclosingClass()
                                .map(outer -> outer.getName().equals(presentation + ".AgentAdminDtos"))
                                .orElse(false),
                        "HTTP record 는 admin.presentation.AgentAdminDtos 안에 있다",
                        events);
                }
                if (!item.getSimpleName().equals("AgentAdminController")) {
                    return;
                }
                require(item, item.isAnnotatedWith(RestController.class), "관리 창구는 RestController 다", events);
                require(
                    item,
                    item.tryGetAnnotationOfType(RequestMapping.class)
                        .map(mapping -> List.of(mapping.value()).equals(List.of("/api/v1/admin/agents")))
                        .orElse(false),
                    "관리 HTTP 경로는 /api/v1/admin/agents 다",
                    events);
                List<JavaMethod> mapped = item.getMethods().stream()
                    .filter(ArchitectureRules::mapped)
                    .toList();
                require(
                    item,
                    mapped.size() == 3
                        && mapped.stream()
                            .map(JavaMethod::getName)
                            .collect(Collectors.toSet())
                            .equals(Set.of("create", "list", "update")),
                    "관리 mapped 메서드는 등록·목록·수정 3개다",
                    events);
            }
        };
    }


    private static boolean mapped(JavaMethod method) {
        return method.isAnnotatedWith(RequestMapping.class) || method.isMetaAnnotatedWith(RequestMapping.class);
    }


    /** rule-10: 까닭과 예외는 docs/code-rules/rule-10.md를 본다. */
    public static final ArchRule ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP = noClasses()
        .that()
        .resideInAPackage("com.bifos.assistant.orchestration..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.bifos.assistant.mcp..")
        .as("orchestration 은 mcp 에 의존하지 않는다");

    /** rule-11: 까닭과 예외는 docs/code-rules/rule-11.md를 본다. */
    public static final ArchRule MCP_DOES_NOT_CALL_HERMES = noClasses()
        .that()
        .resideInAPackage("com.bifos.assistant.mcp..")
        .should()
        .dependOnClassesThat(resideInAPackage("com.bifos.assistant.hermes..")
            .and(simpleNameEndingWith("Client")
                .or(simpleName("HermesRunEventStream"))
                .or(simpleName("HermesProfileKeyStore"))))
        .as("mcp 는 Hermes 를 부르는 타입에 의존하지 않는다");

    /** rule-12: 까닭과 예외는 docs/code-rules/rule-12.md를 본다. */
    public static final ArchRule ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE = noClasses()
        .that()
        .resideInAPackage("com.bifos.assistant.orchestration..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("com.bifos.assistant.chat.turn.application.ChatService")
        .as("orchestration 은 ChatService 에 의존하지 않는다");

    /** rule-13: 까닭과 예외는 docs/code-rules/rule-13.md를 본다. */
    public static final ArchRule HERMES_DOES_NOT_DEPEND_ON_PEOPLE = noClasses()
        .that()
        .resideInAPackage("com.bifos.assistant.hermes..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.bifos.assistant.people..")
        .as("hermes 는 people 에 의존하지 않는다");

    /** rule-14: 까닭과 예외는 docs/code-rules/rule-14.md를 본다. */
    public static final ArchRule NO_JACKSON_2_DATABIND = noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.fasterxml.jackson.core..", "com.fasterxml.jackson.databind..")
        .as("Jackson 2 의 core 와 databind 에 의존하지 않는다");

    /** rule-15: 까닭과 예외는 docs/code-rules/rule-15.md를 본다. */
    public static final ArchRule CONTROLLERS_HAVE_NO_NESTED_RECORDS = classes()
        .that()
        .areRecords()
        .and()
        .areNestedClasses()
        .should(notEnclosedByController())
        .allowEmptyShould(true)
        .as("컨트롤러 안에 record 를 두지 않는다");

    /** rule-16: 까닭과 예외는 docs/code-rules/rule-16.md를 본다. */
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
    private static final List<String> CONTEXT_SPLITTING_FIELD_ANNOTATIONS = List.of(MockitoBean.class.getName(), MockitoSpyBean.class.getName(), TestBean.class.getName());

    /** 메서드에 붙으면 컨텍스트를 나누거나 닫는 주석의 전체 이름이다. */
    private static final List<String> CONTEXT_SPLITTING_METHOD_ANNOTATIONS = List.of(DynamicPropertySource.class.getName(), DirtiesContext.class.getName());

    /** rule-17: 까닭과 예외는 docs/code-rules/rule-17.md를 본다. */
    public static final ArchRule TESTS_DO_NOT_SPLIT_CONTEXT = classes()
        .that(resideOutsideOfPackage("com.bifos.assistant.testsupport.."))
        .and(notMysqlTest())
        .should(notDeclareContextSplitting())
        .allowEmptyShould(true)
        .as("검사 클래스는 Spring 컨텍스트를 나누는 선언을 두지 않는다");

    /** rule-18: 까닭과 예외는 docs/code-rules/rule-18.md를 본다. */
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

    /** rule-19: 까닭과 예외는 docs/code-rules/rule-19.md를 본다. */
    public static final ArchRule NO_DIRECT_INSTANT_NOW = noClasses().should().callMethod(Instant.class, "now").as("Instant.now() 를 직접 부르지 않는다");

    /** rule-20: 까닭과 예외는 docs/code-rules/rule-20.md를 본다. */
    public static final ArchRule MESSAGE_DIGEST_ONLY_IN_SHA256 = noClasses()
        .that()
        .doNotHaveFullyQualifiedName("com.bifos.assistant.shared.util.Sha256")
        .should()
        .callMethod(MessageDigest.class, "getInstance", String.class)
        .as("MessageDigest.getInstance 는 Sha256 만 부른다");

    /** rule-21: 까닭과 예외는 docs/code-rules/rule-21.md를 본다. */
    public static final ArchRule VIRTUAL_THREADS_ONLY_THROUGH_BACKGROUND_TASKS = CompositeArchRule.of(noClasses()
        .that()
        .doNotHaveFullyQualifiedName("com.bifos.assistant.shared.concurrent.VirtualThreadBackgroundTasks")
        .and()
        .doNotHaveFullyQualifiedName("com.bifos.assistant.chat.turn.presentation.ChatEventStreams")
        .and()
        .doNotHaveFullyQualifiedName(
            "com.bifos.assistant.notification.presentation.NotificationEventStreams")
        .should()
        .callMethod(Thread.class, "ofVirtual"))
        .and(noClasses()
            .that()
            .doNotHaveFullyQualifiedName("com.bifos.assistant.shared.concurrent.VirtualThreadBackgroundTasks")
            .and()
            .doNotHaveFullyQualifiedName("com.bifos.assistant.chat.turn.presentation.ChatEventStreams")
            .and()
            .doNotHaveFullyQualifiedName(
                "com.bifos.assistant.notification.presentation.NotificationEventStreams")
            .should()
            .callMethod(Thread.class, "startVirtualThread", Runnable.class))
        .as("가상 스레드는 BackgroundTasks 와 SSE 연결만 직접 띄운다");

    /** rule-22: 까닭과 예외는 docs/code-rules/rule-22.md를 본다. */
    public static final ArchRule NO_UNMANAGED_VIRTUAL_THREAD_EXECUTORS = noClasses()
        .should()
        .callMethod(Executors.class, "newVirtualThreadPerTaskExecutor")
        .as("가상 스레드 실행기는 직접 만들지 않는다");

    /** rule-23: 까닭과 예외는 docs/code-rules/rule-23.md를 본다. */
    public static final ArchRule FEATURE_CONFIG_PLACEMENT = classes()
        .should(new AgentConfigPlacement())
        .as("에이전트 설정은 agent.config에 유일하게 있고 config에는 설정만 둔다");

    /** rule-24: 까닭과 예외는 docs/code-rules/rule-24.md를 본다. */
    public static final ArchRule CONFIGURATION_PROPERTIES_ARE_VALIDATED = classes()
        .that()
        .areAnnotatedWith(ConfigurationProperties.class)
        .should()
        .beAnnotatedWith(Validated.class)
        .allowEmptyShould(true)
        .as("ConfigurationProperties 클래스에는 Validated 가 붙는다");

    /** rule-25: 까닭과 예외는 docs/code-rules/rule-25.md를 본다. */
    public static final ArchRule SERVICES_DO_NOT_EXPOSE_NESTED_TYPES = classes()
        .that()
        .areMemberClasses()
        .and(enclosedByServiceOrInfra())
        .should()
        .bePrivate()
        .allowEmptyShould(true)
        .as("서비스와 infra 안의 중첩 타입은 private 이다");

    /** rule-26: 까닭과 예외는 docs/code-rules/rule-26.md를 본다. */
    public static final ArchRule ENUMERATED_FIELDS_USE_DOMAIN_TYPE = fields().that()
        .areAnnotatedWith(Enumerated.class)
        .and()
        .areDeclaredInClassesThat()
        .areAnnotatedWith(Entity.class)
        .should()
        .haveRawType(resideInAPackage("..domain.type.."))
        .allowEmptyShould(true)
        .as("Enumerated 필드의 타입은 domain.type 에 있다");

    /** rule-27: 까닭과 예외는 docs/code-rules/rule-27.md를 본다. */
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
    private static final String RUN_TIMEOUT_CHECK = "com.bifos.assistant.proactive.check.config.ProactiveCheckProperties$RunTimeoutCheck";

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
        ConnectorPolicyProperties.class,
        BrowserProperties.class,
        WorkspaceProperties.class);

    /** {@code HermesProperties} 가운데 {@code LiveProperties} 로 읽는 칸이다. */
    private static final Set<String> HERMES_LIVE_ACCESSORS = Set.of("runTimeout", "pollInterval");

    /** rule-28: 까닭과 예외는 docs/code-rules/rule-28.md를 본다. */
    public static final ArchRule LIVE_SETTINGS_ONLY_THROUGH_LIVE_PROPERTIES = classes()
        .that()
        .doNotHaveFullyQualifiedName(LIVE_PROPERTIES_CONFIG)
        .and()
        .doNotHaveFullyQualifiedName(RUN_TIMEOUT_CHECK)
        .should(notHoldAny(LIVE_SETTINGS))
        .as("실행 중에 쓰는 설정 record 는 LivePropertiesConfig 만 주입받는다");

    /** rule-29: 까닭과 예외는 docs/code-rules/rule-29.md를 본다. */
    public static final ArchRule HERMES_TIMEOUTS_ONLY_THROUGH_LIVE_PROPERTIES = classes()
        .that()
        .doNotHaveFullyQualifiedName("com.bifos.assistant.hermes.infra.HermesRunEventStream")
        .and()
        .doNotHaveFullyQualifiedName("com.bifos.assistant.hermes.infra.HttpHermesRunsClient")
        .and()
        .doNotHaveFullyQualifiedName(RUN_TIMEOUT_CHECK)
        .should(notReadHermesTimeoutsFromInjectedProperties())
        .as("hermes 의 runTimeout 과 pollInterval 은 LiveProperties 로 읽는다");

    /** rule-30: 까닭과 예외는 docs/code-rules/rule-30.md를 본다. */
    public static final ArchRule ENTITIES_IN_OWNED_ENTITY_PACKAGE = entitiesInOwnedEntityPackage("com.bifos.assistant");

    /** rule-31: 까닭과 예외는 docs/code-rules/rule-31.md를 본다. */
    public static final ArchRule EMBEDDED_IDS_WITH_ENTITIES = embeddedIdsWithEntities("com.bifos.assistant");

    /** rule-70: 까닭과 예외는 docs/code-rules/rule-70.md를 본다. */
    public static final ArchRule APPLICATION_ROOT_HOLDS_ONLY_BEANS = PackageLayoutRules.applicationRootHoldsOnlyBeans(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-71: 까닭과 예외는 docs/code-rules/rule-71.md를 본다. */
    public static final ArchRule APPLICATION_SUBPACKAGES_ARE_DTO_AND_PORT = PackageLayoutRules.applicationSubpackagesAreDtoAndPort(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-72: 까닭과 예외는 docs/code-rules/rule-72.md를 본다. */
    public static final ArchRule PORT_PACKAGE_HOLDS_ONLY_INTERFACES = PackageLayoutRules.portPackageHoldsOnlyInterfaces(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-73: 까닭과 예외는 docs/code-rules/rule-73.md를 본다. */
    public static final ArchRule DTO_PACKAGE_HOLDS_ONLY_VALUES = PackageLayoutRules.dtoPackageHoldsOnlyValues(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-74: 까닭과 예외는 docs/code-rules/rule-74.md를 본다. */
    public static final ArchRule CONFIGURATION_PROPERTIES_IN_CONFIG = PackageLayoutRules.configurationPropertiesInConfig(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-75: 까닭과 예외는 docs/code-rules/rule-75.md를 본다. */
    public static final ArchRule APPLICATION_PACKAGE_SIZE = PackageLayoutRules.applicationPackageSize(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-76: 까닭과 예외는 docs/code-rules/rule-76.md를 본다. */
    public static final ArchRule SUB_FEATURES_FOLLOW_ORDER = PackageLayoutRules.subFeaturesFollowOrder(PackageLayoutRules.PRODUCTION_ROOT, SubFeatureOrder.ORDER);

    /** rule-77: 까닭과 예외는 docs/code-rules/rule-77.md를 본다. */
    public static final ArchRule SPLIT_FEATURES_HAVE_NO_ROOT_ROLES = PackageLayoutRules.splitFeaturesHaveNoRootRoles(PackageLayoutRules.PRODUCTION_ROOT, SubFeatureOrder.ORDER);

    /** rule-78: 까닭과 예외는 docs/code-rules/rule-78.md를 본다. */
    public static final ArchRule SIMPLE_CLASS_NAMES_ARE_UNIQUE = PackageLayoutRules.simpleClassNamesAreUnique(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-79: 까닭과 예외는 docs/code-rules/rule-79.md를 본다. */
    public static final ArchRule ADMIN_PACKAGE_SHAPE = PackageLayoutRules.adminPackageShape(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-80: 까닭과 예외는 docs/code-rules/rule-80.md를 본다. */
    public static final ArchRule FEATURES_DO_NOT_DEPEND_ON_ADMIN = PackageLayoutRules.featuresDoNotDependOnAdmin(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-81: 까닭과 예외는 docs/code-rules/rule-81.md를 본다. */
    public static final ArchRule ADMIN_USES_ONLY_FEATURE_APPLICATION = PackageLayoutRules.adminUsesOnlyFeatureApplication(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-82: 까닭과 예외는 docs/code-rules/rule-82.md를 본다. */
    public static final ArchRule ADMIN_ENDPOINTS_REQUIRE_ADMIN = PackageLayoutRules.adminEndpointsRequireAdmin(PackageLayoutRules.PRODUCTION_ROOT);

    /** rule-83: 까닭과 예외는 docs/code-rules/rule-83.md를 본다. */
    public static final ArchRule REQUIRE_ADMIN_ONLY_IN_ADMIN = PackageLayoutRules.requireAdminOnlyInAdmin(PackageLayoutRules.PRODUCTION_ROOT);

    private static final Map<String, Map<String, String>> OWNED_PERSISTENT_TYPES = Map.of(
        "crypto", Map.of("UserDataKey", "user_data_key"),
        "memory", Map.of(
            "Memory", "memory", "MemoryCapture", "memory_capture", "MemoryCollection", "memory_collection",
            "MemoryCollectionId", "", "MemoryRevision", "memory_revision", "MemoryRevisionId", ""));


    /** fixture 도 운영 소유 목록과 선택 조건을 쓰며 루트와 검사할 기능만 지정한다. */
    static ArchRule entitiesInOwnedEntityPackage(String root, String... features) {
        List<String> owners = features.length == 0 ? List.of("crypto", "memory") : List.of(features);
        return classes().that().resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("소유 저장 타입을 정확히 하나의 domain.entity 에 둔다") {
                private final Map<String, Integer> counts = new HashMap<>();
                private final Map<String, Integer> tables = new HashMap<>();


                @Override
                public void init(Collection<JavaClass> all) {
                    counts.clear();
                    tables.clear();
                }


                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    for (String feature : owners) {
                        String entityPackage = root + "." + feature + ".domain.entity";
                        for (var owner : OWNED_PERSISTENT_TYPES.get(feature).entrySet()) {
                            String key = feature + "." + owner.getKey();
                            boolean ownsTable = !owner.getValue().isEmpty() && item.tryGetAnnotationOfType(Table.class)
                                .map(table -> table.name().equals(owner.getValue())).orElse(false);
                            boolean named = item.getEnclosingClass().isEmpty() && item.getSimpleName().equals(owner.getKey());
                            if (ownsTable) {
                                tables.merge(key, 1, Integer::sum);
                            }
                            if (named || ownsTable) {
                                counts.merge(key, 1, Integer::sum);
                                boolean correctRole = item.isAnnotatedWith(owner.getValue().isEmpty() ? Embeddable.class : Entity.class);
                                if (!item.getPackageName().equals(entityPackage) || !correctRole) {
                                    events.add(SimpleConditionEvent.violated(item,
                                        item.getName() + " 저장 타입의 위치와 역할은 " + entityPackage + " 이다"));
                                }
                            }
                        }
                        if (isOwnedEntity(item, root, List.of(feature)) && !item.getPackageName().equals(entityPackage)) {
                            events.add(SimpleConditionEvent.violated(item,
                                item.getName() + " 엔티티의 위치는 " + entityPackage + " 이다"));
                        }
                    }
                }


                @Override
                public void finish(ConditionEvents events) {
                    for (String feature : owners) {
                        for (var owner : OWNED_PERSISTENT_TYPES.get(feature).entrySet()) {
                            String key = feature + "." + owner.getKey();
                            int count = counts.getOrDefault(key, 0);
                            if (count != 1) {
                                events.add(SimpleConditionEvent.violated(root,
                                    root + "." + key + "/" + owner.getValue() + " 기대 수 1, 실제 " + count));
                            }
                            if (!owner.getValue().isEmpty() && tables.getOrDefault(key, 0) != 1) {
                                events.add(SimpleConditionEvent.violated(root,
                                    root + "." + owner.getValue() + " Table 기대 수 1, 실제 " + tables.getOrDefault(key, 0)));
                            }
                        }
                    }
                }
            }).as("ENTITIES_IN_OWNED_ENTITY_PACKAGE: " + owners + " 저장 타입은 domain.entity 에 정확히 하나씩 둔다");
    }


    /** 이동한 기능의 실제 ID 연결과 entity 패키지 역할만 검사한다. */
    static ArchRule embeddedIdsWithEntities(String root, String... features) {
        List<String> owners = features.length == 0 ? List.of("crypto", "memory") : List.of(features);
        return classes().that().resideInAPackage(root + "..")
            .should(new ArchCondition<JavaClass>("영속 ID 를 소유 엔티티와 같은 패키지에 둔다") {
                private final Set<JavaClass> idClasses = new HashSet<>();
                private int entityCount;


                @Override
                public void init(Collection<JavaClass> all) {
                    idClasses.clear();
                    entityCount = 0;
                    for (JavaClass item : all) {
                        if (isOwnedEntity(item, root, owners)) {
                            entityCount++;
                            idClasses.addAll(persistentIdsOf(item));
                        }
                    }
                }


                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    if (isOwnedEntity(item, root, owners)) {
                        persistentIdsOf(item).forEach(id -> checkIdPackage(item, id, events));
                    }
                    for (String feature : owners) {
                        String entityPackage = root + "." + feature + ".domain.entity";
                        boolean inEntityPackage = item.getPackageName().equals(entityPackage);
                        boolean inSubpackage = item.getPackageName().startsWith(entityPackage + ".");
                        boolean persistent = item.isAnnotatedWith(Entity.class) || idClasses.contains(item);
                        boolean privateNested = item.getEnclosingClass().isPresent()
                            && item.getModifiers().contains(JavaModifier.PRIVATE);
                        boolean privateValue = privateNested && !persistent
                            && !item.isAnnotatedWith(Embeddable.class) && !item.isAnnotatedWith(MappedSuperclass.class);
                        if (inSubpackage || (inEntityPackage && !privateValue && (!persistent || privateNested))) {
                            events.add(SimpleConditionEvent.violated(item,
                                item.getName() + " 은 " + entityPackage
                                    + " 의 엔티티나 실제 영속 ID 또는 private 비영속 구현 값이어야 한다"));
                        }
                    }
                }


                @Override
                public void finish(ConditionEvents events) {
                    if (entityCount == 0) {
                        events.add(SimpleConditionEvent.violated(root,
                            root + " 의 " + String.join(", ", owners) + " 엔티티 검사 대상이 0개다"));
                    }
                }
            }).as("EMBEDDED_IDS_WITH_ENTITIES: " + owners + " 영속 ID 는 엔티티와 같은 패키지에 둔다");
    }


    private static boolean isOwnedEntity(JavaClass item, String root, List<String> owners) {
        return item.isAnnotatedWith(Entity.class) && owners.stream().anyMatch(feature -> item.getPackageName().equals(root + "." + feature) || item.getPackageName().startsWith(root + "." + feature + "."));
    }


    /** 필드·getter 와 영속 상속 선언을 함께 읽어 실제 엔티티에 적용되는 ID 타입만 모은다. */
    private static Set<JavaClass> persistentIdsOf(JavaClass entity) {
        Set<JavaClass> ids = new HashSet<>();
        boolean foundIdClass = false;
        for (JavaClass declaration : entity.getClassHierarchy()) {
            if (!declaration.equals(entity) && !declaration.isAnnotatedWith(MappedSuperclass.class)
                && !declaration.isAnnotatedWith(Entity.class)) {
                continue;
            }
            declaration.getFields().stream().filter(field -> field.isAnnotatedWith(EmbeddedId.class))
                .forEach(field -> ids.add(field.getRawType()));
            declaration.getMethods().stream().filter(method -> method.isAnnotatedWith(EmbeddedId.class))
                .forEach(method -> ids.add(method.getRawReturnType()));
            // 더 가까운 영속 선언의 IdClass 가 상속 선언보다 우선한다.
            if (!foundIdClass && declaration.isAnnotatedWith(IdClass.class)) {
                ids.add(idClassOf(declaration));
                foundIdClass = true;
            }
        }
        return ids;
    }


    private static JavaClass idClassOf(JavaClass item) {
        return (JavaClass)item.getAnnotationOfType(IdClass.class.getName()).get("value").orElseThrow();
    }


    private static void checkIdPackage(JavaClass entity, JavaClass id, ConditionEvents events) {
        if (!entity.getPackageName().equals(id.getPackageName())) {
            events.add(SimpleConditionEvent.violated(entity,
                entity.getName() + " 의 영속 ID " + id.getName() + " 는 소유 엔티티와 같은 패키지여야 한다"));
        }
    }


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
