package com.bifos.assistant.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link ArchitectureRules} 의 규칙을 운영 코드에 건다.
 * 지금 있는 위반은 {@code backend/config/archunit/store/} 의 기준 파일에 얼려 두고 새 위반만 실패시킨다.
 */
@Tag("architecture")
class ArchitectureRulesTest {

    static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.bifos.assistant");

    @Test
    @DisplayName("최상위 패키지 사이에 새 순환 간선이 생기지 않는다")
    void topLevelPackagesFreeOfCycles() {
        FreezingArchRule.freeze(ArchitectureRules.TOP_LEVEL_PACKAGES_FREE_OF_CYCLES).check(MAIN);
    }

    @Test
    @DisplayName("shared 가 다른 최상위 패키지를 새로 쓰지 않는다")
    void sharedDoesNotDependOnDomains() {
        FreezingArchRule.freeze(ArchitectureRules.SHARED_DOES_NOT_DEPEND_ON_DOMAINS).check(MAIN);
    }

    @Test
    @DisplayName("아래 층이 위 층을 새로 쓰지 않는다")
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
        FreezingArchRule.freeze(ArchitectureRules.ORCHESTRATION_DOES_NOT_DEPEND_ON_MCP).check(MAIN);
    }

    @Test
    @DisplayName("mcp 가 Hermes 를 부르는 타입을 쓰지 않는다")
    void mcpDoesNotCallHermes() {
        FreezingArchRule.freeze(ArchitectureRules.MCP_DOES_NOT_CALL_HERMES).check(MAIN);
    }

    @Test
    @DisplayName("orchestration 이 ChatService 를 쓰지 않는다")
    void orchestrationDoesNotCallChatService() {
        FreezingArchRule.freeze(ArchitectureRules.ORCHESTRATION_DOES_NOT_CALL_CHAT_SERVICE).check(MAIN);
    }

    @Test
    @DisplayName("hermes 가 people 을 쓰지 않는다")
    void hermesDoesNotDependOnPeople() {
        FreezingArchRule.freeze(ArchitectureRules.HERMES_DOES_NOT_DEPEND_ON_PEOPLE).check(MAIN);
    }

    @Test
    @DisplayName("Jackson 2 의 core 와 databind 를 쓰지 않는다")
    void noJackson2Databind() {
        FreezingArchRule.freeze(ArchitectureRules.NO_JACKSON_2_DATABIND).check(MAIN);
    }

    @Test
    @DisplayName("컨트롤러 안에 record 를 두지 않는다")
    void controllersHaveNoNestedRecords() {
        FreezingArchRule.freeze(ArchitectureRules.CONTROLLERS_HAVE_NO_NESTED_RECORDS).check(MAIN);
    }
}
