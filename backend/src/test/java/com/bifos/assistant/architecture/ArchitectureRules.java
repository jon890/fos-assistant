package com.bifos.assistant.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Optional;

/**
 * backend 의 구조 규칙이다. 문서는 규칙을 이 클래스의 상수 이름으로 가리킨다.
 *
 * <p>규칙의 {@code as(...)} 설명이 기준 파일의 열쇠다. 설명을 바꾸면 그 규칙을 다시 얼린다.
 * 기준 파일을 갱신하는 방법은 {@code backend/AGENTS.md} 의 「구조 규칙」 절에 있다.
 */
public final class ArchitectureRules {

    /**
     * 최상위 패키지 사이의 간선이 순환에 속하지 않는다. {@code shared} 는 그래프에서 뺀다.
     *
     * <p>근거: {@code docs/code-architecture.md} 「backend 패키지」 의 {@code mcp} 와 {@code orchestration} 문단.
     */
    public static final ArchRule TOP_LEVEL_PACKAGES_FREE_OF_CYCLES = classes()
            .that()
            .resideInAPackage("com.bifos.assistant..")
            .should(new TopLevelPackageCycles())
            .as("최상위 패키지 사이의 간선은 순환에 속하지 않는다");

    /**
     * {@code shared} 는 다른 최상위 패키지에 의존하지 않는다.
     *
     * <p>근거: {@code docs/code-architecture.md} 「backend 패키지」 의 {@code shared/auth}, {@code shared/error} 책임.
     */
    public static final ArchRule SHARED_DOES_NOT_DEPEND_ON_DOMAINS = noClasses()
            .that()
            .resideInAPackage("com.bifos.assistant.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.bifos.assistant.agent..",
                    "com.bifos.assistant.chat..",
                    "com.bifos.assistant.context..",
                    "com.bifos.assistant.hermes..",
                    "com.bifos.assistant.mcp..",
                    "com.bifos.assistant.memory..",
                    "com.bifos.assistant.orchestration..",
                    "com.bifos.assistant.people..",
                    "com.bifos.assistant.skill..",
                    "com.bifos.assistant.usage..",
                    "com.bifos.assistant.user..")
            .as("shared 는 다른 최상위 패키지에 의존하지 않는다");

    /**
     * 도메인 안은 {@code presentation} 에서 {@code application}, {@code domain}, {@code infra} 로만 흐른다.
     * 아래 층이 위 층을 쓰는 것만 막는다. {@code presentation} 이 {@code infra} 를 바로 쓰는 것은 막지 않는다.
     *
     * <p>근거: {@code backend/AGENTS.md} 「패키지 배치」, {@code docs/code-architecture.md} 「backend 패키지」.
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
            .mayOnlyBeAccessedByLayers("presentation", "application")
            .whereLayer("domain")
            .mayOnlyBeAccessedByLayers("presentation", "application", "infra")
            .as("층은 presentation 에서 application, infra, domain 쪽으로만 흐른다");

    /**
     * {@code domain} 은 웹 계층의 타입에 의존하지 않는다.
     *
     * <p>근거: {@code backend/AGENTS.md} 「패키지 배치」, {@code docs/code-architecture.md} 「backend 패키지」.
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
     * <p>근거: {@code docs/code-architecture.md} 「backend 패키지」 의
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
     * {@code hermes.HermesProfileName} 같은 이름 규칙 값은 허용한다.
     *
     * <p>근거: {@code docs/code-architecture.md} 「다른 에이전트에게 맡기기」 의 「MCP 쪽은 Hermes 를 부르지 않는다」.
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
     * <p>근거: {@code docs/code-architecture.md} 「중지」 의 「{@code ChatService} 와 흐름이 서로를 부르지 않게」.
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
     * <p>근거: {@code docs/code-architecture.md} 「사용자를 더할 때」 의
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

    private ArchitectureRules() {}

    private static ArchCondition<JavaClass> notEnclosedByController() {
        return new ArchCondition<>("바깥 클래스의 단순 이름이 Controller 로 끝나지 않는다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaClass> enclosing = item.getEnclosingClass();
                while (enclosing.isPresent()) {
                    if (enclosing.get().getSimpleName().endsWith("Controller")) {
                        events.add(SimpleConditionEvent.violated(
                                item, item.getName() + " 가 컨트롤러 " + enclosing.get().getName() + " 안에 있다"));
                        return;
                    }
                    enclosing = enclosing.get().getEnclosingClass();
                }
            }
        };
    }
}
