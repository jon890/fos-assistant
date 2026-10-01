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

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
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
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/**
 * backend 의 구조 규칙이다. 문서는 규칙을 이 클래스의 상수 이름으로 가리킨다.
 *
 * <p>규칙의 {@code as(...)} 설명이 기준 파일의 열쇠다. 설명을 바꾸면 그 규칙을 다시 얼린다.
 * 기준 파일을 갱신하는 방법은 {@code backend/AGENTS.md} 의 「구조 규칙」 절에 있다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ArchitectureRules {

    /** Spring 의 {@code Transactional} 을 import 하므로 Jakarta 쪽은 이 상수에서만 전체 이름으로 쓴다. */
    private static final Class<? extends Annotation> JAKARTA_TRANSACTIONAL =
            jakarta.transaction.Transactional.class; // 전체 이름 허용: Spring 과 Jakarta 의 Transactional 이름이 같다

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
            .dependOnClassesThat(resideInAPackage("com.bifos.assistant.(*)..")
                    .and(resideOutsideOfPackage("com.bifos.assistant.shared..")))
            .as("shared 는 다른 최상위 패키지에 의존하지 않는다");

    /**
     * 도메인 안은 {@code presentation} 에서 {@code application} 을 거쳐 {@code infra} 와 {@code domain} 으로 흐른다.
     * 아래 층이 위 층을 쓰는 것과 {@code presentation} 이 {@code infra} 를 바로 쓰는 것을 막는다.
     * 컨트롤러가 저장소를 바로 쓰면 권한 확인과 트랜잭션 경계를 서비스가 갖지 못한다.
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
            .mayOnlyBeAccessedByLayers("application")
            .whereLayer("domain")
            .mayOnlyBeAccessedByLayers("presentation", "application", "infra")
            .as("층은 presentation 에서 application 을 거쳐 infra 와 domain 으로 흐른다");

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

    /**
     * {@code @Test} 와 {@code @ParameterizedTest} 메서드에는 {@code @DisplayName} 이 붙는다.
     * 메서드 이름은 영문 camelCase 이고, 테스트 보고서에 보이는 한국어 문장은 {@code @DisplayName} 이 갖는다.
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
     * {@code application} 밖의 클래스와 메서드에는 {@code @Transactional} 을 붙이지 않는다.
     * Spring 과 Jakarta 의 두 {@code @Transactional} 을 모두 막는다.
     * 트랜잭션 경계는 유스케이스를 아는 층이 정한다. 컨트롤러와 저장소에 두면 경계가 둘로 갈린다.
     *
     * <p>근거: {@code backend/AGENTS.md} 「구조 규칙」.
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
     * 시각을 주입받아야 테스트가 시각을 고정한다. {@code Clock} 을 받는 {@code Instant.now(Clock)} 은 허용한다.
     *
     * <p>근거: {@code backend/AGENTS.md} 「구조 규칙」.
     */
    public static final ArchRule NO_DIRECT_INSTANT_NOW =
            noClasses().should().callMethod(Instant.class, "now").as("Instant.now() 를 직접 부르지 않는다");

    /**
     * {@code MessageDigest.getInstance} 는 {@code shared.util.Sha256} 만 부른다.
     * 해시 구현을 한 곳에 둔다.
     *
     * <p>근거: {@code backend/AGENTS.md} 「구조 규칙」.
     */
    public static final ArchRule MESSAGE_DIGEST_ONLY_IN_SHA256 = noClasses()
            .that()
            .doNotHaveFullyQualifiedName("com.bifos.assistant.shared.util.Sha256")
            .should()
            .callMethod(MessageDigest.class, "getInstance", String.class)
            .as("MessageDigest.getInstance 는 Sha256 만 부른다");

    /**
     * {@code @ConfigurationProperties} 클래스에는 {@code @Validated} 도 붙는다.
     * 잘못된 설정은 기동에서 멈춘다.
     *
     * <p>근거: {@code backend/AGENTS.md} 「구조 규칙」.
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
     * <p>근거: {@code backend/AGENTS.md} 「데이터 클래스는 컨트롤러 안에 두지 않는다」 와 같은 까닭이다.
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
     * 저장되는 값은 바꾸면 마이그레이션을 판단해야 하므로 한곳에 모아 보이게 한다.
     * 저장되지 않는 서비스 결과와 화면용 enum 은 {@code <기능>.application.model} 에 두고,
     * {@code ErrorCode} 는 {@code shared.error} 에 둔다. 이 둘은 규칙으로 검사하지 않는다.
     *
     * <p>근거: {@code backend/AGENTS.md} 「구조 규칙」.
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
     * 저장되는 enum 은 가장 아래 층이다.
     *
     * <p>근거: {@code backend/AGENTS.md} 「구조 규칙」.
     */
    public static final ArchRule DOMAIN_TYPE_DEPENDS_ON_NOTHING_ABOVE = noClasses()
            .that()
            .resideInAPackage("..domain.type..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application..", "..infra..", "..presentation..")
            .allowEmptyShould(true)
            .as("domain.type 은 위 층에 의존하지 않는다");

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
