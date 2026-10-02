package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.bifos.assistant.connector.application.ConnectorToolPolicies;
import com.bifos.assistant.connector.domain.ToolPolicy;
import com.bifos.assistant.connector.domain.type.ToolApproval;
import com.bifos.assistant.connector.domain.type.ToolRisk;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorFieldOptions;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnectorToolPoliciesTest {
    private static final ConnectorTool VERIFY = new ConnectorTool("check", "READ", "none", null, null);
    private static final ConnectorTool OPTIONS = new ConnectorTool("list_scopes", "READ", "none", null, null);

    @Test
    @DisplayName("WRITE 에 none 을 선언한 manifest 는 하한보다 느슨해 받지 않는다")
    void rejectsWriteToolDeclaredWithoutApproval() {
        assertThat(ConnectorToolPolicies.valid(
                        manifest(2, VERIFY, OPTIONS, new ConnectorTool("write_note", "WRITE", "none", null, null))))
                .isFalse();
    }

    @Test
    @DisplayName("위험도마다 하한의 승인 방식은 받고 그보다 느슨한 것은 받지 않는다")
    void acceptsFloorApprovalAndRejectsLooserForEachRisk() {
        for (ToolRisk risk : ToolRisk.values()) {
            for (ToolApproval approval : ToolApproval.values()) {
                String word = approval.name().toLowerCase();
                boolean valid = ConnectorToolPolicies.valid(
                        manifest(2, VERIFY, OPTIONS, new ConnectorTool("tool", risk.name(), word, null, null)));

                assertThat(valid).as("%s 에 %s", risk, word).isEqualTo(!approval.looserThan(risk.floor()));
            }
        }
        assertThat(ToolRisk.READ.floor()).isEqualTo(ToolApproval.NONE);
        assertThat(ToolRisk.SENSITIVE.floor()).isEqualTo(ToolApproval.REQUIRED);
        assertThat(ToolRisk.WRITE.floor()).isEqualTo(ToolApproval.REQUIRED);
        assertThat(ToolRisk.DESTRUCTIVE.floor()).isEqualTo(ToolApproval.ALWAYS);
        assertThat(ToolRisk.FINANCIAL.floor()).isEqualTo(ToolApproval.ALWAYS);
    }

    @Test
    @DisplayName("DESTRUCTIVE 에 always 를 선언한 manifest 는 받는다")
    void acceptsDestructiveToolDeclaredWithAlways() {
        assertThat(ConnectorToolPolicies.valid(manifest(
                        2, VERIFY, OPTIONS, new ConnectorTool("purge_notes", "DESTRUCTIVE", "always", null, null))))
                .isTrue();
    }

    @Test
    @DisplayName("도구를 선언하는 판에서 확인 도구나 선택지 도구가 tools 에 없으면 받지 않는다")
    void rejectsDeclaringManifestMissingVerifyOrOptionsTool() {
        assertThat(ConnectorToolPolicies.valid(manifest(2, OPTIONS))).isFalse();
        assertThat(ConnectorToolPolicies.valid(manifest(2, VERIFY))).isFalse();
        assertThat(ConnectorToolPolicies.valid(manifest(2))).isFalse();
    }

    @Test
    @DisplayName("확인 도구와 선택지 도구는 READ 와 none 으로 선언해야 받는다")
    void rejectsVerifyOrOptionsToolNotDeclaredAsReadWithoutApproval() {
        assertThat(ConnectorToolPolicies.valid(
                        manifest(2, new ConnectorTool("check", "READ", "required", null, null), OPTIONS)))
                .isFalse();
        assertThat(ConnectorToolPolicies.valid(
                        manifest(2, VERIFY, new ConnectorTool("list_scopes", "SENSITIVE", "required", null, null))))
                .isFalse();
    }

    @Test
    @DisplayName("모르는 위험도나 승인 방식, 비어 있는 선언은 받지 않는다")
    void rejectsUnknownRiskOrApproval() {
        assertThat(ConnectorToolPolicies.valid(
                        manifest(2, VERIFY, OPTIONS, new ConnectorTool("tool", "HARMLESS", "none", null, null))))
                .isFalse();
        assertThat(ConnectorToolPolicies.valid(
                        manifest(2, VERIFY, OPTIONS, new ConnectorTool("tool", "read", "none", null, null))))
                .isFalse();
        assertThat(ConnectorToolPolicies.valid(
                        manifest(2, VERIFY, OPTIONS, new ConnectorTool("tool", "WRITE", "sometimes", null, null))))
                .isFalse();
        assertThat(ConnectorToolPolicies.valid(
                        manifest(2, VERIFY, OPTIONS, new ConnectorTool("tool", null, null, null, null))))
                .isFalse();
    }

    @Test
    @DisplayName("도구를 선언하지 않는 판은 tools 가 비어도 받고, 1 과 2 가 아닌 판은 받지 않는다")
    void acceptsLegacySchemaWithoutToolsAndRejectsUnknownSchema() {
        assertThat(ConnectorToolPolicies.valid(manifest(1))).isTrue();
        assertThat(ConnectorToolPolicies.valid(manifest(1, VERIFY, OPTIONS))).isTrue();
        assertThat(ConnectorToolPolicies.valid(manifest(0))).isFalse();
        assertThat(ConnectorToolPolicies.valid(manifest(3, VERIFY, OPTIONS))).isFalse();
    }

    @Test
    @DisplayName("find 는 이름이 같은 선언을 주고 null 이름과 선언하지 않은 이름에는 빈 값을 준다")
    void findsDeclaredPolicyByNameAndReturnsEmptyForNullOrUnknown() {
        ConnectorManifest manifest =
                manifest(2, VERIFY, OPTIONS, new ConnectorTool("write_note", "WRITE", "always", "메모 쓰기", null));

        assertThat(ConnectorToolPolicies.find(manifest, "write_note"))
                .contains(new ToolPolicy(ToolRisk.WRITE, ToolApproval.ALWAYS, "메모 쓰기", false));
        assertThat(ConnectorToolPolicies.find(manifest, null)).isEmpty();
        assertThat(ConnectorToolPolicies.find(manifest, "hidden_tool")).isEmpty();
    }

    @Test
    @DisplayName("required 도구는 grant 가 없거나 참이면 상시 허락을 줄 수 있고 거짓이면 줄 수 없다")
    void readsGrantOfRequiredTool() {
        ConnectorManifest manifest = manifest(
                2,
                VERIFY,
                OPTIONS,
                new ConnectorTool("absent", "WRITE", "required", null, null),
                new ConnectorTool("open", "WRITE", "required", null, Boolean.TRUE),
                new ConnectorTool("closed", "WRITE", "required", null, Boolean.FALSE));

        assertThat(ConnectorToolPolicies.valid(manifest)).isTrue();
        assertThat(ConnectorToolPolicies.find(manifest, "absent"))
                .contains(new ToolPolicy(ToolRisk.WRITE, ToolApproval.REQUIRED, null, true));
        assertThat(ConnectorToolPolicies.find(manifest, "open"))
                .contains(new ToolPolicy(ToolRisk.WRITE, ToolApproval.REQUIRED, null, true));
        assertThat(ConnectorToolPolicies.find(manifest, "closed"))
                .contains(new ToolPolicy(ToolRisk.WRITE, ToolApproval.REQUIRED, null, false));
    }

    @Test
    @DisplayName("none 이나 always 인 도구는 grant 가 참이어도 상시 허락을 줄 수 없다")
    void toolWithoutRequiredApprovalIsNeverGrantable() {
        ConnectorManifest manifest = manifest(
                2,
                VERIFY,
                OPTIONS,
                new ConnectorTool("read_note", "READ", "none", null, Boolean.TRUE),
                new ConnectorTool("send_note", "WRITE", "always", null, Boolean.TRUE));

        assertThat(ConnectorToolPolicies.find(manifest, "read_note"))
                .contains(new ToolPolicy(ToolRisk.READ, ToolApproval.NONE, null, false));
        assertThat(ConnectorToolPolicies.find(manifest, "send_note"))
                .contains(new ToolPolicy(ToolRisk.WRITE, ToolApproval.ALWAYS, null, false));
    }

    @Test
    @DisplayName("승인 방식은 none, required, always 순서로 엄격해지고 모르는 글은 null 로 읽는다")
    void ordersApprovalsAndReadsWords() {
        assertThat(ToolApproval.NONE.looserThan(ToolApproval.REQUIRED)).isTrue();
        assertThat(ToolApproval.REQUIRED.looserThan(ToolApproval.ALWAYS)).isTrue();
        assertThat(ToolApproval.REQUIRED.looserThan(ToolApproval.REQUIRED)).isFalse();
        assertThat(ToolApproval.ALWAYS.looserThan(ToolApproval.NONE)).isFalse();
        assertThat(ToolApproval.fromWord("none")).isEqualTo(ToolApproval.NONE);
        assertThat(ToolApproval.fromWord("required")).isEqualTo(ToolApproval.REQUIRED);
        assertThat(ToolApproval.fromWord("always")).isEqualTo(ToolApproval.ALWAYS);
        assertThat(ToolApproval.fromWord("NONE")).isNull();
        assertThat(ToolApproval.fromWord(null)).isNull();
        assertThat(ToolRisk.READ.defaultApproval()).isEqualTo(ToolApproval.NONE);
        assertThat(ToolRisk.SENSITIVE.defaultApproval()).isEqualTo(ToolApproval.REQUIRED);
        assertThat(ToolRisk.WRITE.defaultApproval()).isEqualTo(ToolApproval.REQUIRED);
        assertThat(ToolRisk.DESTRUCTIVE.defaultApproval()).isEqualTo(ToolApproval.ALWAYS);
        assertThat(ToolRisk.FINANCIAL.defaultApproval()).isEqualTo(ToolApproval.ALWAYS);
    }

    /** 확인 도구가 {@code check} 이고 선택지 도구가 {@code list_scopes} 인 커넥터다. */
    private static ConnectorManifest manifest(int schema, ConnectorTool... tools) {
        return new ConnectorManifest(
                "demo-notes",
                "검사용 메모",
                "",
                List.of(
                        new ConnectorField("token", "DEMO_TOKEN", "토큰", "", true, true, null, null),
                        new ConnectorField(
                                "scope",
                                "DEMO_SCOPE",
                                "범위",
                                "",
                                false,
                                false,
                                null,
                                new ConnectorFieldOptions("list_scopes", "scopes", "id", "name", true))),
                "check",
                "demo",
                List.of(),
                false,
                schema,
                List.of(tools));
    }
}
