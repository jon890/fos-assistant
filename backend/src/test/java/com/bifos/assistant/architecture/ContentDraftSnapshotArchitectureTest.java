package com.bifos.assistant.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.contentdraft.application.port.ContentDraftJobQueries;
import com.bifos.assistant.chat.contentdraft.application.port.ContentDraftSaveRequests;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftJobPage;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftJobView;
import com.bifos.assistant.chat.contentdraft.application.dto.ContentDraftSaveRequestView;
import com.bifos.assistant.connector.contentdraft.application.ContentDraftJobs;
import com.bifos.assistant.shared.auth.CurrentUser;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 원고 저장의 chat port 와 connector 구현이 층 순서를 거스르지 않는지 기준 파일 없이 본다. */
@Tag("architecture")
class ContentDraftSnapshotArchitectureTest {
    private static final Map<String, String> OWNED = Map.of(
        "ContentDraftSaveRequests", "com.bifos.assistant.chat.contentdraft.application.port",
        "ContentDraftJobQueries", "com.bifos.assistant.chat.contentdraft.application.port",
        "ContentDraftSaveRequestView", "com.bifos.assistant.chat.contentdraft.application.dto",
        "ContentDraftJobView", "com.bifos.assistant.chat.contentdraft.application.dto",
        "ContentDraftJobPage", "com.bifos.assistant.chat.contentdraft.application.dto",
        "ContentDraftJobs", "com.bifos.assistant.connector.contentdraft.application",
        "ContentDraftSaveApproval", "com.bifos.assistant.connector.contentdraft.application",
        "ContentDraftSaveTicket", "com.bifos.assistant.connector.contentdraft.application",
        "ContentDraftJobAuthorizer", "com.bifos.assistant.connector.contentdraft.application",
        "PreparedContentDraftApproval", "com.bifos.assistant.connector.contentdraft.application.dto");


    @Test
    @DisplayName("새 원고 저장 타입은 각각 하나이고 정해진 패키지에 있다")
    void placesEachNewTypeOnce() {
        OWNED.forEach((name, owner) -> assertThat(ArchitectureRulesTest.MAIN.stream()
            .filter(type -> type.getSimpleName().equals(name)).map(JavaClass::getPackageName).toList())
                .as("운영 타입 %s", name).containsExactly(owner));
    }


    @Test
    @DisplayName("connector 구현이 두 chat port 를 정확한 반환 타입으로 구현한다")
    void implementsPortsWithExactReturnTypes() throws Exception {
        assertThat(ContentDraftSaveRequests.class).isAssignableFrom(ContentDraftJobs.class);
        assertThat(ContentDraftJobQueries.class).isAssignableFrom(ContentDraftJobs.class);
        assertThat(Arrays.stream(ContentDraftSaveRequests.class.getMethods()).<Class<?>>map(method -> method.getReturnType()).toList())
            .containsExactly(ContentDraftSaveRequestView.class);
        assertThat(ContentDraftJobQueries.class.getMethod("refresh", CurrentUser.class, Long.class,
            UUID.class, UUID.class).getReturnType()).isEqualTo(ContentDraftJobView.class);
        assertThat(ContentDraftJobQueries.class.getMethod("list", CurrentUser.class, Long.class,
            UUID.class, int.class).getReturnType()).isEqualTo(ContentDraftJobPage.class);
    }


    @Test
    @DisplayName("chat 의 원고 타입은 connector 와 mcp 를 쓰지 않고 새 타입의 간선은 층 순서와 순환 규칙을 그대로 통과한다")
    void keepsLayerOrderWithoutBaseline() {
        noClasses().that().resideInAPackage("com.bifos.assistant.chat..").and().haveSimpleNameStartingWith("ContentDraft")
            .should().dependOnClassesThat().resideInAnyPackage("com.bifos.assistant.connector..", "com.bifos.assistant.mcp..")
            .check(ArchitectureRulesTest.MAIN);
        var newTypes = classes().that().haveSimpleNameStartingWith("ContentDraft").or().haveSimpleName("PreparedContentDraftApproval")
            .or().haveSimpleName("ConnectorActionApproval");
        newTypes.should(new TopLevelPackageOrder()).check(ArchitectureRulesTest.MAIN);
        newTypes.should(new TopLevelPackageCycles()).check(ArchitectureRulesTest.MAIN);
    }
}
