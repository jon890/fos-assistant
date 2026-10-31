package com.bifos.assistant.connector;

import static com.bifos.assistant.connector.action.domain.ToolPolicyDecision.CheckBoundary.APPROVAL_ONLY;
import static com.bifos.assistant.connector.action.domain.ToolPolicyDecision.CheckBoundary.NOT_CHECK;
import static com.bifos.assistant.connector.action.domain.ToolPolicyDecision.CheckBoundary.READ_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bifos.assistant.connector.action.domain.ToolPolicyDecision;
import com.bifos.assistant.connector.action.domain.type.ActionDecision;
import com.bifos.assistant.connector.action.domain.type.ActionDenyReason;
import com.bifos.assistant.connector.connection.application.ConnectorToolPolicies;
import com.bifos.assistant.connector.connection.domain.type.ConnectionStatus;
import com.bifos.assistant.connector.connection.domain.type.ToolApproval;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.hermes.infra.HttpHermesConnectorClient;
import com.bifos.assistant.hermes.infra.SandboxAttachmentDirectory;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@code assistant.json} 이 없는 표준 플러그인(`schema: 3`)을 카탈로그에서 읽고 그 도구를 호출마다 승인으로 판정한다
 * (ADR-20261101 / plugin-without-assistant-json).
 *
 * <p>카탈로그 항목은 대시보드 plugin 이 {@code hermes/tests/fixtures/standard-plugin} 과
 * {@code standard-remote-plugin} 으로 내는 모양과 같다. 그 모양은 {@code hermes/tests/test_connector_standard_plugin.py} 가 검사한다.
 */
class StandardPluginPolicyTest {
    private static final String BASE = "https://dashboard.example.test";
    private static final String CATALOG = """
        [{"id":"standard-notes","schema":3,"title":"표준 메모","description":"","icon":null,"link":null,
          "readme_url":null,"link_kind":"service",
          "fields":[{"key":"std_token","env":"STD_TOKEN","label":"STD_TOKEN","secret":true,"required":true},
                    {"key":"std_region","env":"STD_REGION","label":"STD_REGION","secret":true,"required":false}],
          "verify":null,"mcp_server":"std-notes","owner_browser":false,"owner_browser_login_url":null,
          "single_binding":false,"skills":["standard-notes"],"unknown_errors":{},"tools":{}},
         {"id":"standard-remote","schema":3,"title":"standard-remote","description":"","icon":null,"link":null,
          "readme_url":null,"link_kind":"service",
          "fields":[{"key":"std_remote_token","env":"STD_REMOTE_TOKEN","label":"STD_REMOTE_TOKEN","secret":true,
                     "required":true}],
          "verify":null,"mcp_server":"std-remote","owner_browser":false,"owner_browser_login_url":null,
          "single_binding":false,"skills":["remote-notes"],"unknown_errors":{},"tools":{}}]
        """;
    private static final ToolPolicyDecision EVERY_CALL = new ToolPolicyDecision(
        ActionDecision.NEEDS_APPROVAL, null, ToolRisk.WRITE, ToolApproval.REQUIRED);

    private HttpHermesConnectorClient client;
    private MockRestServiceServer server;

    @TempDir
    Path attachmentRoot;


    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpHermesConnectorClient(
            new HermesProperties("unused", BASE, "test-dashboard-token", BASE, null, null, null, null),
            new SandboxAttachmentDirectory(attachmentRoot.toString()));
        ReflectionTestUtils.setField(client, "client", builder.build());
    }


    @Test
    @DisplayName("표준 플러그인은 확인 도구와 도구 선언 없이 카탈로그에 오르고 정책을 읽을 수 있는 커넥터다")
    void standardPluginsReachTheCatalogWithoutVerifyTool() {
        List<ConnectorManifest> catalog = readCatalog();

        assertThat(catalog).extracting(ConnectorManifest::id).containsExactly("standard-notes", "standard-remote");
        for (ConnectorManifest manifest : catalog) {
            assertThat(manifest.schema()).isEqualTo(ConnectorToolPolicies.STANDARD_SCHEMA);
            assertThat(manifest.verifyTool()).isNull();
            assertThat(manifest.tools()).isEmpty();
            assertThat(ConnectorToolPolicies.valid(manifest)).as(manifest.id()).isTrue();
        }
        assertThat(catalog.getFirst().skills()).containsExactly("standard-notes");
    }


    @Test
    @DisplayName("표준 플러그인의 도구는 상시 허락과 대화 허락이 있어도 호출마다 승인 필요로 판정한다")
    void everyToolOfStandardPluginNeedsApprovalEachCall() {
        for (ConnectorManifest manifest : readCatalog()) {
            String hermesTool = "mcp__" + manifest.mcpServer().replace('-', '_') + "__delete_note";
            assertThat(ConnectorToolPolicies.find(manifest, "delete_note")).isEmpty();
            for (boolean granted : new boolean[] {false, true}) {
                assertThat(ToolPolicyDecision.decide(
                    ConnectionStatus.READY, hermesTool.startsWith("mcp__std_"), manifest.schema(), Optional.empty(),
                    granted, granted, 2, NOT_CHECK))
                        .as("%s granted=%s", manifest.id(), granted)
                        .isEqualTo(EVERY_CALL);
            }
            // 쓰기를 허용한 살펴보기도 승인 줄로 보낸다. 읽기 경계의 살펴보기는 승인 줄 없이 거절한다.
            assertThat(ToolPolicyDecision.decide(
                ConnectionStatus.READY, true, manifest.schema(), Optional.empty(), false, 2, APPROVAL_ONLY))
                    .isEqualTo(EVERY_CALL);
            assertThat(ToolPolicyDecision.decide(
                ConnectionStatus.READY, true, manifest.schema(), Optional.empty(), false, 2, READ_ONLY).denyReason())
                    .isEqualTo(ActionDenyReason.READ_ONLY_RUN);
            // 다른 서버의 등록 이름은 schema 와 상관없이 거절한다.
            assertThat(ToolPolicyDecision.decide(
                ConnectionStatus.READY, false, manifest.schema(), Optional.empty(), true, 2, NOT_CHECK).denyReason())
                    .isEqualTo(ActionDenyReason.UNDECLARED);
        }
    }


    @Test
    @DisplayName("schema 3 이 아닌 항목에서 확인 도구가 빠지면 지금처럼 카탈로그 읽기가 실패한다")
    void verifyToolIsStillRequiredForDeclaredSchemas() {
        server.expect(requestTo(BASE + "/api/connectors/catalog?include=standard"))
            .andRespond(withSuccess(CATALOG.replace("\"schema\":3", "\"schema\":2"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::readCatalog).isInstanceOf(RuntimeException.class);
    }


    @Test
    @DisplayName("schema 3 에 도구 선언이나 확인 도구가 실려 오면 그 커넥터를 없는 것으로 다룬다")
    void standardSchemaWithDeclarationsIsInvalid() {
        ConnectorManifest manifest = readCatalog().getFirst();
        ConnectorManifest withVerify = new ConnectorManifest(manifest.id(), manifest.title(), manifest.description(),
            manifest.fields(), "list_notes", manifest.mcpServer(), manifest.schema(), manifest.tools());

        assertThat(ConnectorToolPolicies.valid(withVerify)).isFalse();
    }


    private List<ConnectorManifest> readCatalog() {
        server.expect(requestTo(BASE + "/api/connectors/catalog?include=standard"))
            .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        List<ConnectorManifest> catalog = client.readCatalog();
        server.verify();
        return catalog;
    }
}
