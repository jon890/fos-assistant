package com.bifos.assistant.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.chat.application.ContentDraftJobQueries;
import com.bifos.assistant.chat.application.ContentDraftSaveRequests;
import com.bifos.assistant.chat.application.model.ContentDraftJobPage;
import com.bifos.assistant.chat.application.model.ContentDraftJobView;
import com.bifos.assistant.chat.application.model.ContentDraftSaveRequestView;
import com.bifos.assistant.connector.application.ContentDraftJobs;
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
        "ContentDraftSaveRequests", "com.bifos.assistant.chat.application",
        "ContentDraftJobQueries", "com.bifos.assistant.chat.application",
        "ContentDraftSaveRequestView", "com.bifos.assistant.chat.application.model",
        "ContentDraftJobView", "com.bifos.assistant.chat.application.model",
        "ContentDraftJobPage", "com.bifos.assistant.chat.application.model",
        "ContentDraftJobs", "com.bifos.assistant.connector.application",
        "ContentDraftSaveApproval", "com.bifos.assistant.connector.application",
        "ContentDraftSaveTicket", "com.bifos.assistant.connector.application",
        "ContentDraftJobAuthorizer", "com.bifos.assistant.connector.application",
        "PreparedContentDraftApproval", "com.bifos.assistant.connector.application.model");

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
