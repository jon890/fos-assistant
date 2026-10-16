package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bifos.assistant.hermes.HermesConnectorClient.InstallResult;
import com.bifos.assistant.hermes.dto.CallResult;
import com.bifos.assistant.hermes.dto.ConnectorAppearance;
import com.bifos.assistant.hermes.dto.ConnectorCallError;
import com.bifos.assistant.hermes.dto.ConnectorErrorDetail;
import com.bifos.assistant.hermes.dto.ConnectorField;
import com.bifos.assistant.hermes.dto.ConnectorFieldOptions;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import com.bifos.assistant.hermes.dto.ConnectorRecovery;
import com.bifos.assistant.hermes.dto.ConnectorTool;
import com.bifos.assistant.hermes.dto.ConnectorUnknownDetail;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class HttpHermesConnectorClientTest {
    private static final String BASE = "https://dashboard.example.test";
    private static final String DEMO = "demo-notes";
    private static final String PROFILE = "user-demo";
    private static final String TOKEN = "demo_ok_0123456789";
    private static final String CATALOG = """
            [{"id":"demo-notes","title":"검사용 메모","description":"검사에서만 쓰는 커넥터입니다.",
              "fields":[
                {"key":"token","env":"DEMO_TOKEN","label":"토큰","description":"검사용 토큰입니다.",
                 "secret":true,"required":true,"pattern":"^demo_[a-z]+_[0-9]{10}$"},
                {"key":"scope","env":"DEMO_SCOPE","label":"범위","required":false,
                 "options":{"tool":"list_scopes","items":"scopes","value":"id","label":"name",
                            "auto_select_single":true}}],
              "verify":{"tool":"list_scopes"},"mcp_server":"demo"}]
            """;
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
        // 실행 경로는 읽기 제한이 다른 클라이언트를 쓴다. 같은 대역 서버에 붙인다.
        ReflectionTestUtils.setField(client, "executeClient", builder.build());
        ReflectionTestUtils.setField(client, "prepareClient", builder.build());
    }


    @Test
    @DisplayName("실제 Python producer의 Gmail과 네이버 카탈로그 인자 이름을 읽는다")
    void readsArgumentLabelsFromPythonCatalog() throws Exception {
        String raw = Files.readString(Path.of("../test/resources/connector-argument-catalog.json"));
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(raw, MediaType.APPLICATION_JSON));
        List<ConnectorManifest> catalog = client.readCatalog();
        assertThat(catalog).hasSize(2);
        assertThat(catalog.getFirst().tools().stream().filter(tool -> tool.name().equals("send_message"))
            .findFirst().orElseThrow().argLabels()).containsEntry("to", "받는 사람");
        assertThat(catalog.getLast().tools().stream().filter(tool -> tool.name().equals("save_draft"))
            .findFirst().orElseThrow().argLabels()).containsEntry("title", "제목");
        server.verify();
    }


    @Test
    @DisplayName("잘못된 인자 표시 이름만 버리고 도구 정책과 카탈로그는 유지한다")
    void dropsMalformedArgumentLabelsWithoutDroppingCatalog() {
        String tail = "\"mcp_server\":\"demo\"";
        String policies = ",\"schema\":2,\"tools\":{"
            + "\"valid\":{\"risk\":\"WRITE\",\"arg_labels\":{\"to\":\"받는 사람\"}},"
            + "\"nested\":{\"risk\":\"WRITE\",\"arg_labels\":{\"to\":{}}},"
            + "\"array\":{\"risk\":\"WRITE\",\"arg_labels\":[]},"
            + "\"old\":{\"risk\":\"WRITE\"}}";
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(CATALOG.replace(tail, tail + policies), MediaType.APPLICATION_JSON));
        List<ConnectorTool> tools = client.readCatalog().getFirst().tools();
        assertThat(tools).extracting(ConnectorTool::argLabels)
            .containsExactly(Map.of("to", "받는 사람"), Map.of(), Map.of(), Map.of());
        assertThat(tools).extracting(ConnectorTool::risk).containsOnly("WRITE");
        server.verify();
    }


    @Test
    @DisplayName("snapshot 다섯 필드와 compile 전용 capability를 읽고 옛 생성자를 보존한다")
    void readsCompileOnlySnapshotDeclaration() {
        String declaration = "\"snapshot\":{\"protocol\":\"content-snapshot-v1\",\"compile_tool\":\"compile_snapshot\","
            + "\"start_tool\":\"start_snapshot\",\"status_tool\":\"status_snapshot\","
            + "\"capabilities\":{\"compile\":true,\"start\":false,\"status\":false}}";
        String tail = "\"mcp_server\":\"demo\"";
        server.expect(requestTo(BASE + "/api/connectors/catalog")).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(CATALOG.replace(tail, tail + "," + declaration), MediaType.APPLICATION_JSON));
        for (String broken : List.of(declaration.replace("\"compile\":true", "\"compile\":false"),
            declaration.replace("\"status\":false", "\"status\":true"),
            declaration.replace("\"compile_snapshot\"", "\"start_snapshot\""),
            declaration.replace(",\"status\":false", ""))) {
            server.expect(requestTo(BASE + "/api/connectors/catalog"))
                .andRespond(withSuccess(CATALOG.replace(tail, tail + "," + broken), MediaType.APPLICATION_JSON));
        }
        assertThat(client.readCatalog().getFirst().snapshot()).isNull();
        var current = client.readCatalog().getFirst();
        assertThat(current.snapshot().capabilities()).containsEntry("compile", true).containsEntry("start", false).containsEntry("status", false);
        var old = new ConnectorManifest(current.id(), current.title(), current.description(), current.fields(), current.verifyTool(),
            current.mcpServer(), current.schema(), current.tools(), current.skills(),
            current.appearance(), current.singleBinding(), current.ownerBrowser(), current.ownerBrowserLoginUrl(),
            current.executionGuard(), current.executionGuardManifestSha256());
        assertThat(old.snapshot()).isNull();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(client::readCatalog).isInstanceOf(IllegalStateException.class);
        }
        server.verify();
    }


    @Test
    @DisplayName("바인딩 보호 맥락은 선택 본문으로 전달하고 옛 상태의 누락은 미지원이다")
    void bindingGuardIsOptionalAndOldStateIsUnsupported() {
        var guard = JsonMapper.builder().build().readTree("""
                {"v":1,"protocol":"approval-claim-v2","bindingId":"14","connectionId":"13",
                 "connectionUpdatedAt":"2026-10-10T00:00:00Z","manifestSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """);
        server.expect(requestTo(BASE + "/api/connectors"))
            .andExpect(content().json("""
                        {"profile":"user-demo","plugin":"demo-notes","enabled":true,"sandbox_owner":"owner",
                         "bind":{"vault":"c1","guard":{"v":1,"protocol":"approval-claim-v2","bindingId":"14","connectionId":"13",
                         "connectionUpdatedAt":"2026-10-10T00:00:00Z","manifestSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}}
                        """, JsonCompareMode.STRICT))
            .andRespond(withSuccess("""
                        {"profile":"user-demo","plugin":"demo-notes","enabled":true,"restart_required":false}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors?profile=" + PROFILE))
            .andRespond(withSuccess("""
                        {"profile":"user-demo","connectors":[{"plugin":"demo-notes","enabled":true,"configured":true,"mode":"bind"}]}
                        """, MediaType.APPLICATION_JSON));
        client.bindConnector(PROFILE, DEMO, "c1", "owner", null, guard);
        assertThat(client.readConnector(PROFILE, DEMO)
            .executionGuard()
            .path("state")
            .stringValue())
                .isEqualTo("unsupported");
        server.verify();
    }


    @Test
    @DisplayName("보호 선언은 원형으로 전달하고 같은 manifest의 SHA-256도 보존한다")
    void preservesExecutionGuardWithoutCoercion() {
        String manifestSha256 = "a".repeat(64);
        String raw = CATALOG.replace(
            "\"mcp_server\":\"demo\"",
            "\"mcp_server\":\"demo\"," + "\"execution_guard\":{\"protocol\":false,\"extra\":7},"
                + "\"execution_guard_manifest_sha256\":\"" + manifestSha256 + "\"");
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(raw, MediaType.APPLICATION_JSON));
        ConnectorManifest manifest = client.readCatalog().getFirst();
        var guard = manifest.executionGuard();
        assertThat(guard.propertyNames()).containsExactlyInAnyOrder("protocol", "extra");
        assertThat(guard.get("protocol").isBoolean()).isTrue();
        assertThat(guard.get("extra").intValue()).isEqualTo(7);
        assertThat(manifest.executionGuardManifestSha256()).isEqualTo(manifestSha256);
        server.verify();
    }


    @Test
    @DisplayName("카탈로그와 보호 선언의 중복 키 및 뒤따르는 JSON을 원문 파싱에서 거절한다")
    void rejectsDuplicateAndTrailingCatalogJson() {
        for (String raw : new String[] {
            CATALOG + "[]",
            CATALOG.replace(
                "\"mcp_server\":\"demo\"",
                "\"mcp_server\":\"demo\",\"execution_guard\":{\"protocol\":1,\"protocol\":2}")
        }) {
            server.reset();
            server.expect(requestTo(BASE + "/api/connectors/catalog"))
                .andRespond(withSuccess(raw, MediaType.APPLICATION_JSON));
            assertThatThrownBy(client::readCatalog)
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
        }
    }


    @Test
    @DisplayName("옛 카탈로그의 보호 선언 누락은 null로 남는다")
    void preservesMissingExecutionGuardAsUnsupported() {
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        ConnectorManifest manifest = client.readCatalog().getFirst();
        assertThat(manifest.executionGuard()).isNull();
        assertThat(manifest.executionGuardManifestSha256()).isNull();
    }


    @DisplayName("카탈로그에서 칸의 env, 선택지, 확인 도구, MCP 서버 이름을 읽는다")
    @Test
    void readsCatalogWithEnvOptionsVerifyToolAndMcpServer() {
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer test-dashboard-token"))
            .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));

        List<ConnectorManifest> catalog = client.readCatalog();

        assertThat(catalog)
            .containsExactly(new ConnectorManifest(
                DEMO,
                "검사용 메모",
                "검사에서만 쓰는 커넥터입니다.",
                List.of(
                    new ConnectorField(
                        "token",
                        "DEMO_TOKEN",
                        "토큰",
                        "검사용 토큰입니다.",
                        true,
                        true,
                        "^demo_[a-z]+_[0-9]{10}$",
                        null),
                    new ConnectorField(
                        "scope",
                        "DEMO_SCOPE",
                        "범위",
                        "",
                        false,
                        false,
                        null,
                        new ConnectorFieldOptions("list_scopes", "scopes", "id", "name", true))),
                "list_scopes",
                "demo",
                1,
                List.of()));
        server.verify();
    }


    @DisplayName("카탈로그의 아이콘과 링크를 읽고, 칸이 없는 옛 응답은 둘 다 비우고, 틀린 아이콘은 그 칸만 버린다")
    @Test
    void readsIconAndLinkAndDropsOnlyTheBrokenField() {
        String url = BASE + "/api/connectors/catalog";
        String tail = "\"mcp_server\":\"demo\"";
        String svg = Base64.getEncoder()
            .encodeToString("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8));
        String script = Base64.getEncoder().encodeToString("<svg><script/></svg>".getBytes(StandardCharsets.UTF_8));
        String link = ",\"link\":\"https://notes.example.test/\"";
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                CATALOG.replace(
                    tail,
                    tail + ",\"icon\":{\"media_type\":\"image/svg+xml\",\"data\":\"" + svg + "\"}" + link),
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                CATALOG.replace(
                    tail,
                    tail + ",\"icon\":{\"media_type\":\"image/svg+xml\",\"data\":\"" + script + "\"}"
                        + link),
                MediaType.APPLICATION_JSON));

        ConnectorManifest declared = client.readCatalog().get(0);
        ConnectorManifest old = client.readCatalog().get(0);
        List<ConnectorManifest> broken = client.readCatalog();

        assertThat(declared.appearance())
            .isEqualTo(new ConnectorAppearance("data:image/svg+xml;base64," + svg, "https://notes.example.test/"));
        assertThat(old.appearance()).isEqualTo(ConnectorAppearance.NONE);
        assertThat(broken).extracting(ConnectorManifest::id).containsExactly(DEMO);
        assertThat(broken.get(0).appearance()).isEqualTo(new ConnectorAppearance(null, "https://notes.example.test/"));
        server.verify();
    }


    @DisplayName("HTTP 카탈로그의 두 링크를 독립 검증하고 기존 정책과 구버전 응답을 유지한다")
    @Test
    void readsReadmeAndProjectFromHttpCatalog() {
        String catalog = CATALOG.replace("\"items\":\"scopes\"", "\"items\":\"result.scopes\"")
            .replace("\"value\":\"id\"", "\"value\":\"identity.id\"")
            .replace("\"label\":\"name\"", "\"label\":\"display.name\"");
        String tail = "\"mcp_server\":\"demo\"";
        String metadata = ",\"link\":\"http://invalid.example.test/\",\"readme_url\":\"https://example.com/README.md\",\"link_kind\":\"project\"";
        String guard = ",\"execution_guard\":{\"protocol\":false,\"extra\":7},"
            + "\"execution_guard_manifest_sha256\":\"" + "a".repeat(64) + "\"";
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(catalog.replace(tail, tail + metadata + guard), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(catalog, MediaType.APPLICATION_JSON));
        ConnectorManifest read = client.readCatalog().get(0);
        ConnectorManifest legacy = client.readCatalog().get(0);
        assertThat(read.appearance()).isEqualTo(new ConnectorAppearance(null, null, "https://example.com/README.md", "project"));
        assertThat(read.tools()).isEqualTo(legacy.tools());
        assertThat(read.fields()).isEqualTo(legacy.fields());
        var options = read.fields().get(1).options();
        assertThat(options.items()).isEqualTo("result.scopes");
        assertThat(options.value()).isEqualTo("identity.id");
        assertThat(options.label()).isEqualTo("display.name");
        assertThat(read.executionGuard().get("protocol").isBoolean()).isTrue();
        assertThat(read.executionGuard().propertyNames()).containsExactlyInAnyOrder("protocol", "extra");
        assertThat(read.executionGuardManifestSha256()).isEqualTo("a".repeat(64));
        assertThat(legacy.executionGuard()).isNull();
        assertThat(legacy.executionGuardManifestSha256()).isNull();
        assertThat(legacy.appearance()).isEqualTo(ConnectorAppearance.NONE);
        server.verify();
    }


    @DisplayName("카탈로그의 owner_browser 와 로그인 주소를 읽고, 칸이 없는 옛 카탈로그는 거짓과 null 이며 https 가 아닌 주소는 버린다")
    @Test
    void readsOwnerBrowserAndLoginUrl() {
        String url = BASE + "/api/connectors/catalog";
        String tail = "\"mcp_server\":\"demo\"";
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                CATALOG.replace(
                    tail,
                    tail + ",\"owner_browser\":true,"
                        + "\"owner_browser_login_url\":\"https://login.example.test/sign-in\""),
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                CATALOG.replace(
                    tail,
                    tail + ",\"owner_browser\":true,"
                        + "\"owner_browser_login_url\":\"http://login.example.test/\""),
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                CATALOG.replace(tail, tail + ",\"owner_browser\":\"true\""), MediaType.APPLICATION_JSON));

        ConnectorManifest declared = client.readCatalog().get(0);
        ConnectorManifest old = client.readCatalog().get(0);
        ConnectorManifest plainHttp = client.readCatalog().get(0);

        assertThat(declared.ownerBrowser()).isTrue();
        assertThat(declared.ownerBrowserLoginUrl()).isEqualTo("https://login.example.test/sign-in");
        assertThat(old.ownerBrowser()).isFalse();
        assertThat(old.ownerBrowserLoginUrl()).isNull();
        assertThat(plainHttp.ownerBrowser()).isTrue();
        assertThat(plainHttp.ownerBrowserLoginUrl()).isNull();
        assertThatThrownBy(() -> client.readCatalog()).isInstanceOf(IllegalStateException.class);
        server.verify();
    }


    @DisplayName("카탈로그의 single_binding 을 읽고, 칸이 없으면 거짓이며 boolean 이 아니면 거절한다")
    @Test
    void readsDeclaredSingleBinding() {
        String url = BASE + "/api/connectors/catalog";
        String tail = "\"mcp_server\":\"demo\"";
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                CATALOG.replace(tail, tail + ",\"single_binding\":true"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                CATALOG.replace(tail, tail + ",\"single_binding\":\"true\""), MediaType.APPLICATION_JSON));

        assertThat(client.readCatalog().get(0).singleBinding()).isTrue();
        assertThat(client.readCatalog().get(0).singleBinding()).isFalse();
        assertThatThrownBy(() -> client.readCatalog()).isInstanceOf(IllegalStateException.class);
        server.verify();
    }


    @DisplayName("카탈로그의 schema 와 tools 를 받은 글자 그대로 읽고, title 이 없으면 null 이다")
    @Test
    void readsSchemaAndToolPolicies() {
        String tail = "\"mcp_server\":\"demo\"";
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(
                CATALOG.replace(
                    tail,
                    tail + ",\"schema\":2,\"tools\":{"
                        + "\"list_scopes\":{\"risk\":\"READ\",\"approval\":\"none\"},"
                        + "\"write_note\":{\"risk\":\"WRITE\",\"approval\":\"required\","
                        + "\"title\":\"메모 쓰기\"},"
                        + "\"odd\":{\"risk\":\"UNKNOWN\"}}"),
                MediaType.APPLICATION_JSON));

        ConnectorManifest declared = client.readCatalog().get(0);

        assertThat(declared.schema()).isEqualTo(2);
        assertThat(declared.tools())
            .containsExactly(
                new ConnectorTool("list_scopes", "READ", "none", null, null),
                new ConnectorTool("write_note", "WRITE", "required", "메모 쓰기", null),
                // 모르는 위험도와 빠진 승인 방식은 여기서 거르지 않는다. 받는 쪽이 그 커넥터만 뺀다.
                new ConnectorTool("odd", "UNKNOWN", null, null, null));
        server.verify();
    }


    @DisplayName("도구의 grant 는 칸이 없으면 null, boolean 이면 그 값이고 그 밖의 모양은 거짓으로 읽는다")
    @Test
    void readsGrantOfToolPolicies() {
        String tail = "\"mcp_server\":\"demo\"";
        String required = "{\"risk\":\"WRITE\",\"approval\":\"required\"";
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(
                CATALOG.replace(
                    tail,
                    tail + ",\"schema\":2,\"tools\":{"
                        + "\"absent\":" + required + "},"
                        + "\"open\":" + required + ",\"grant\":true},"
                        + "\"closed\":" + required + ",\"grant\":false},"
                        + "\"word\":" + required + ",\"grant\":\"true\"},"
                        + "\"number\":" + required + ",\"grant\":1},"
                        + "\"empty\":" + required + ",\"grant\":null}}"),
                MediaType.APPLICATION_JSON));

        ConnectorManifest declared = client.readCatalog().get(0);

        assertThat(declared.tools())
            .extracting(ConnectorTool::name, ConnectorTool::grant)
            .containsExactly(
                tuple("absent", null),
                tuple("open", true),
                tuple("closed", false),
                // 읽을 수 없는 선언은 상시 허락을 여는 쪽으로 읽지 않는다.
                tuple("word", false),
                tuple("number", false),
                tuple("empty", false));
        server.verify();
    }


    @DisplayName("도구의 outbound 는 칸이 없으면 null, boolean 이면 그 값이고 그 밖의 모양은 참으로 읽는다")
    @Test
    void readsOutboundOfToolPolicies() {
        String tail = "\"mcp_server\":\"demo\"";
        String required = "{\"risk\":\"WRITE\",\"approval\":\"required\"";
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(
                CATALOG.replace(
                    tail,
                    tail + ",\"schema\":2,\"tools\":{"
                        + "\"absent\":" + required + "},"
                        + "\"inside\":" + required + ",\"outbound\":false},"
                        + "\"outside\":" + required + ",\"outbound\":true},"
                        + "\"word\":" + required + ",\"outbound\":\"false\"},"
                        + "\"empty\":" + required + ",\"outbound\":null}}"),
                MediaType.APPLICATION_JSON));

        ConnectorManifest declared = client.readCatalog().get(0);

        assertThat(declared.tools())
            .extracting(ConnectorTool::name, ConnectorTool::outbound)
            .containsExactly(
                tuple("absent", null),
                tuple("inside", false),
                tuple("outside", true),
                // 읽을 수 없는 선언은 대화 허락을 여는 쪽으로 읽지 않는다.
                tuple("word", true),
                tuple("empty", true));
        server.verify();
    }


    @DisplayName("도구의 conversation_grant 는 칸이 없으면 null, boolean 이면 그 값이고 그 밖의 모양은 거짓으로 읽는다")
    @Test
    void readsConversationGrantOfToolPolicies() {
        String tail = "\"mcp_server\":\"demo\"";
        String required = "{\"risk\":\"WRITE\",\"approval\":\"required\",\"outbound\":false";
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(
                CATALOG.replace(
                    tail,
                    tail + ",\"schema\":2,\"tools\":{"
                        + "\"absent\":" + required + "},"
                        + "\"open\":" + required + ",\"conversation_grant\":true},"
                        + "\"closed\":" + required + ",\"conversation_grant\":false},"
                        + "\"word\":" + required + ",\"conversation_grant\":\"true\"},"
                        + "\"empty\":" + required + ",\"conversation_grant\":null}}"),
                MediaType.APPLICATION_JSON));

        ConnectorManifest declared = client.readCatalog().get(0);

        assertThat(declared.tools())
            .extracting(ConnectorTool::name, ConnectorTool::conversationGrant)
            .containsExactly(
                tuple("absent", null),
                tuple("open", true),
                tuple("closed", false),
                // 읽을 수 없는 선언은 대화 허락을 여는 쪽으로 읽지 않는다.
                tuple("word", false),
                tuple("empty", false));
        server.verify();
    }


    @DisplayName("도구의 identifiers 는 문자열 배열이면 그 순서대로 읽고, 칸이 없거나 그 밖의 모양이면 빈 목록으로 읽는다")
    @Test
    void readsIdentifiersOfToolPolicies() {
        String tail = "\"mcp_server\":\"demo\"";
        String required = "{\"risk\":\"WRITE\",\"approval\":\"required\"";
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(
                CATALOG.replace(
                    tail,
                    tail + ",\"schema\":2,\"tools\":{"
                        + "\"absent\":" + required + "},"
                        + "\"listed\":" + required + ",\"identifiers\":[\"filter_id\",\"label\"]},"
                        + "\"word\":" + required + ",\"identifiers\":\"filter_id\"},"
                        + "\"mixed\":" + required + ",\"identifiers\":[\"filter_id\",1]}}"),
                MediaType.APPLICATION_JSON));

        ConnectorManifest declared = client.readCatalog().get(0);

        assertThat(declared.tools())
            .extracting(ConnectorTool::name, ConnectorTool::identifiers)
            .containsExactly(
                tuple("absent", List.of()),
                tuple("listed", List.of("filter_id", "label")),
                // 읽을 수 없는 선언은 가림을 푸는 쪽으로 읽지 않는다.
                tuple("word", List.of()),
                tuple("mixed", List.of()));
        server.verify();
    }


    @DisplayName("schema 가 정수가 아니거나 tools 가 객체가 아닌 커넥터는 받는 쪽이 거르는 값으로 읽고 다른 커넥터는 그대로 읽는다")
    @Test
    void readsMalformedSchemaOrToolsAsUnjudgeableWithoutFailingCatalog() {
        String url = BASE + "/api/connectors/catalog";
        String tail = "\"mcp_server\":\"demo\"";
        String other = ",{\"id\":\"other-notes\",\"title\":\"다른 메모\",\"fields\":[],"
            + "\"verify\":{\"tool\":\"list_scopes\"},\"mcp_server\":\"other\",\"schema\":2,"
            + "\"tools\":{\"list_scopes\":{\"risk\":\"READ\",\"approval\":\"none\"}}}]";
        String declared = ",\"tools\":{\"list_scopes\":{\"risk\":\"READ\",\"approval\":\"none\"}}";
        List<String> malformed = List.of(
            // 판이 글자다. 도구 선언이 멀쩡해도 함께 버린다.
            ",\"schema\":\"2\"" + declared,
            // 도구 선언이 배열이다. 판이 없어 도구를 선언하지 않는 판으로 읽히면 안 된다.
            ",\"tools\":[\"list_scopes\"]",
            ",\"schema\":2,\"tools\":[\"list_scopes\"]");
        for (String broken : malformed) {
            String catalog = CATALOG.replace(tail, tail + broken).strip();
            server.expect(requestTo(url))
                .andRespond(withSuccess(
                    catalog.substring(0, catalog.length() - 1) + other, MediaType.APPLICATION_JSON));
        }

        for (String broken : malformed) {
            List<ConnectorManifest> read = client.readCatalog();

            assertThat(read)
                .as("틀린 선언 %s", broken)
                .extracting(ConnectorManifest::id)
                .containsExactly(DEMO, "other-notes");
            assertThat(read.get(0).schema()).as("틀린 선언 %s 의 판", broken).isZero();
            assertThat(read.get(0).tools()).as("틀린 선언 %s 의 도구", broken).isEmpty();
            assertThat(read.get(1).schema()).isEqualTo(2);
            assertThat(read.get(1).tools())
                .containsExactly(new ConnectorTool("list_scopes", "READ", "none", null, null));
        }
        server.verify();
    }


    @DisplayName("설치 목록의 policy_hook 은 JSON true 일 때만 참이다")
    @Test
    void readsPolicyHookOnlyWhenJsonTrue() {
        String url = BASE + "/api/connectors?profile=user-demo";
        String connectors = "\"connectors\":[{\"plugin\":\"demo-notes\",\"enabled\":true,\"configured\":true,\"mode\":\"bind\"}]}";
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"policy_hook\":true," + connectors, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"policy_hook\":false," + connectors, MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"policy_hook\":\"true\"," + connectors,
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"policy_hook\":true,\"connectors\":[]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.readConnector(PROFILE, DEMO).policyHook()).isTrue();
        assertThat(client.readConnector(PROFILE, DEMO).policyHook()).isFalse();
        assertThat(client.readConnector(PROFILE, DEMO).policyHook()).isFalse();
        // 설치되지 않은 것으로 읽는 응답도 최상위의 값을 그대로 담는다.
        assertThat(client.readConnector(PROFILE, DEMO).policyHook()).isTrue();
        server.verify();
    }


    @DisplayName("바인딩 설치 응답의 reload_pending 을 읽고, 없으면 거짓이다")
    @Test
    void readsReloadPendingFromBindResponse() {
        String body = "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":true,\"restart_required\":false";
        server.expect(requestTo(BASE + "/api/connectors"))
            .andRespond(withSuccess(body + ",\"reload_pending\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors"))
            .andRespond(withSuccess(body + "}", MediaType.APPLICATION_JSON));

        assertThat(client.bindConnector(PROFILE, DEMO, "c7", "u1", null))
            .isEqualTo(new InstallResult(false, false, true));
        assertThat(client.bindConnector(PROFILE, DEMO, "c7", "u1", null))
            .isEqualTo(new InstallResult(false, false, false));
        server.verify();
    }


    @DisplayName("커넥터가 하나도 없는 카탈로그는 빈 목록이다")
    @Test
    void readsEmptyCatalog() {
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.readCatalog()).isEmpty();
        server.verify();
    }


    @DisplayName("모양이 틀린 카탈로그를 거절한다")
    @Test
    void rejectsMalformedCatalog() {
        String url = BASE + "/api/connectors/catalog";
        server.expect(requestTo(url)).andRespond(withSuccess("{\"connectors\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                CATALOG.replace("\"mcp_server\":\"demo\"", "\"mcp_server\":7"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(CATALOG.replace("\"env\":\"DEMO_TOKEN\",", ""), MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                CATALOG.replace("\"verify\":{\"tool\":\"list_scopes\"},", ""), MediaType.APPLICATION_JSON));

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> client.readCatalog()).isInstanceOf(IllegalStateException.class);
        }
        server.verify();
    }


    @DisplayName("도구 호출은 커넥터 경로에 도구와 값을 보내고 성공 결과를 돌려준다")
    @Test
    void callSendsToolAndValuesAndReturnsResult() {
        server.expect(requestTo(BASE + "/api/connectors/demo-notes/call"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-dashboard-token"))
            .andExpect(content().json("{\"tool\":\"list_scopes\",\"values\":{\"token\":\"" + TOKEN + "\"}}"))
            .andRespond(withSuccess(
                "{\"ok\":true,\"result\":{\"scopes\":[{\"id\":\"a\",\"name\":\"범위 A\"}]}}",
                MediaType.APPLICATION_JSON));

        CallResult result = client.call(DEMO, "list_scopes", Map.of("token", TOKEN), null);

        assertThat(result.ok()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.result().get("scopes").get(0).get("id").asString()).isEqualTo("a");
        server.verify();
    }


    @DisplayName("도구 호출의 실패는 공통 어휘 하나로 읽는다")
    @Test
    void callReadsFailureAsCommonErrorWord() {
        String url = BASE + "/api/connectors/demo-notes/call";
        for (String word : List.of("credential_rejected", "forbidden", "invalid_input", "unavailable")) {
            server.expect(requestTo(url))
                .andRespond(withSuccess("{\"ok\":false,\"error\":\"" + word + "\"}", MediaType.APPLICATION_JSON));
        }

        assertThat(List.of(
            client.call(DEMO, "list_scopes", Map.of(), null),
            client.call(DEMO, "list_scopes", Map.of(), null),
            client.call(DEMO, "list_scopes", Map.of(), null),
            client.call(DEMO, "list_scopes", Map.of(), null)))
                .extracting(CallResult::error)
                .containsExactly(
                    ConnectorCallError.CREDENTIAL_REJECTED,
                    ConnectorCallError.FORBIDDEN,
                    ConnectorCallError.INVALID_INPUT,
                    ConnectorCallError.UNAVAILABLE);
        server.verify();
    }


    @DisplayName("실행은 profile 과 등록 이름과 JSON 값으로 읽은 인자를 보내고 성공 결과를 돌려준다")
    @Test
    void executeSendsProfileToolAndArgumentsAsJsonAndReturnsResult() {
        server.expect(requestTo(BASE + "/api/connectors/demo-notes/execute"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-dashboard-token"))
            .andExpect(content()
                .json(
                    "{\"profile\":\"user-demo\",\"hermes_tool\":\"mcp__demo__write_note\","
                        + "\"args\":{\"text\":\"안녕\",\"count\":2}}",
                    JsonCompareMode.STRICT))
            .andRespond(withSuccess("{\"ok\":true,\"result\":{\"saved\":true}}", MediaType.APPLICATION_JSON));

        // 저장한 글의 공백은 값에 들지 않는다. 값이 같은 JSON 으로 나간다.
        CallResult result = client.execute(PROFILE, DEMO, "mcp__demo__write_note", "{\"text\":\"안녕\",   \"count\":2}");

        assertThat(result.ok()).isTrue();
        assertThat(result.result().get("saved").asBoolean()).isTrue();
        server.verify();
    }


    @DisplayName("실행의 200 실패 응답은 공통 어휘 하나로 읽는다")
    @Test
    void executeReadsFailureAsCommonErrorWord() {
        server.expect(requestTo(BASE + "/api/connectors/demo-notes/execute"))
            .andRespond(withSuccess("{\"ok\":false,\"error\":\"forbidden\"}", MediaType.APPLICATION_JSON));

        CallResult result = client.execute(PROFILE, DEMO, "mcp__demo__write_note", "{}");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(ConnectorCallError.FORBIDDEN);
        server.verify();
    }


    @DisplayName("실행의 실패 응답에 실린 오류 코드와 정수 세부와 복구 어휘를 읽고, 글 세부와 모르는 복구 어휘는 버린다")
    @Test
    void executeReadsDeclaredErrorDetail() {
        server.expect(requestTo(BASE + "/api/connectors/demo-notes/execute"))
            .andRespond(withSuccess(
                "{\"ok\":false,\"error\":\"invalid_input\",\"code\":\"GMAIL_TARGET_COUNT_CHANGED\","
                    + "\"recovery\":\"recheck\",\"details\":{\"actual_count\":17,\"message\":\"raw\"}}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors/demo-notes/execute"))
            .andRespond(withSuccess(
                "{\"ok\":false,\"error\":\"forbidden\",\"code\":\"GMAIL_FORBIDDEN\",\"recovery\":\"call_us\"}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors/demo-notes/execute"))
            .andRespond(withSuccess(
                "{\"ok\":false,\"error\":\"forbidden\",\"code\":\"Request had insufficient scopes\"}",
                MediaType.APPLICATION_JSON));

        CallResult changed = client.execute(PROFILE, DEMO, "mcp__demo__write_note", "{}");
        CallResult forbidden = client.execute(PROFILE, DEMO, "mcp__demo__write_note", "{}");
        CallResult raw = client.execute(PROFILE, DEMO, "mcp__demo__write_note", "{}");

        assertThat(changed.error()).isEqualTo(ConnectorCallError.INVALID_INPUT);
        assertThat(changed.detail())
            .isEqualTo(new ConnectorErrorDetail(
                "GMAIL_TARGET_COUNT_CHANGED", Map.of("actual_count", 17L), ConnectorRecovery.RECHECK));
        assertThat(forbidden.detail()).isEqualTo(new ConnectorErrorDetail("GMAIL_FORBIDDEN", Map.of(), null));
        assertThat(raw.error()).isEqualTo(ConnectorCallError.FORBIDDEN);
        assertThat(raw.detail()).as("코드 형식이 아닌 글은 코드로 읽지 않는다").isNull();
        server.verify();
    }


    @DisplayName("실행의 504 와 5xx 와 읽을 수 없는 본문은 원문 없는 ConnectorExecutionUnknown 이다")
    @Test
    void executeTreatsTimeoutServerErrorAndUnreadableBodyAsUnknown() {
        String url = BASE + "/api/connectors/demo-notes/execute";
        server.expect(requestTo(url))
            .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT).body("{\"detail\":\"remote secret detail\"}"));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(url)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess("{\"ok\":false,\"error\":\"unheard_of\"}", MediaType.APPLICATION_JSON));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> client.execute(PROFILE, DEMO, "mcp__demo__write_note", "{}"))
                .as("%d번째 응답", attempt + 1)
                .isInstanceOfSatisfying(ConnectorExecutionUnknown.class, ex -> {
                    assertThat(ex.getMessage()).isNull();
                    assertThat(ex.getCause()).isNull();
                    assertThat(ex.detail()).isEmpty();
                });
        }
        server.verify();
    }


    @DisplayName("실행의 504 본문에서 선언한 오류 코드와 재시도 식별자만 읽고 다른 칸과 식별자 형식이 아닌 값은 버린다")
    @Test
    void executeReadsDeclaredCodeAndRetryIdentifierFromUnknownAnswer() {
        String url = BASE + "/api/connectors/demo-notes/execute";
        String attemptId = "0b7f3c1e-5d2a-4c8e-9f61-2a3b4c5d6e7f";
        // career 의 5xx 를 대시보드 plugin 이 옮긴 모양이다. plugin 이 걸러야 하는 칸이 섞여 와도 읽지 않는다.
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT).body(
            "{\"detail\":\"도구가 실행 결과를 모른다고 답했다\",\"code\":\"CAREER_OUTCOME_UNKNOWN\","
                + "\"retry\":{\"attemptId\":\"" + attemptId + "\"},\"message\":\"raw\",\"answer\":\"private answer\"}"));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT).body(
            "{\"code\":\"CAREER_OUTCOME_UNKNOWN\",\"retry\":{\"attemptId\":\"me@example.com\"}}"));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT).body(
            "{\"code\":\"CAREER_OUTCOME_UNKNOWN\",\"retry\":{\"attemptId\":\"a\",\"other\":\"b\"}}"));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT).body(
            "{\"code\":\"Request had insufficient scopes\",\"retry\":{\"attemptId\":\"a\"}}"));

        ConnectorUnknownDetail declared = unknownDetail();
        assertThat(declared).isEqualTo(new ConnectorUnknownDetail("CAREER_OUTCOME_UNKNOWN", "attemptId", attemptId));
        assertThat(declared.toStored()).doesNotContain("private answer").doesNotContain("raw");
        assertThat(unknownDetail()).as("식별자 형식이 아닌 값").isEqualTo(
            new ConnectorUnknownDetail("CAREER_OUTCOME_UNKNOWN", null, null));
        assertThat(unknownDetail()).as("칸이 둘인 retry").isEqualTo(
            new ConnectorUnknownDetail("CAREER_OUTCOME_UNKNOWN", null, null));
        assertThat(unknownDetail()).as("코드 형식이 아닌 글").isNull();
        server.verify();
    }


    private ConnectorUnknownDetail unknownDetail() {
        try {
            client.execute(PROFILE, DEMO, "mcp__demo__write_note", "{}");
        } catch (ConnectorExecutionUnknown ex) {
            return ex.detail().orElse(null);
        }
        throw new AssertionError("결과를 모르는 실행이어야 한다");
    }


    @DisplayName("실행의 400 과 401 과 404 는 실행되지 않은 것이라 unavailable 실패 결과다")
    @Test
    void executeReadsRefusalsAsNotExecutedFailure() {
        String url = BASE + "/api/connectors/demo-notes/execute";
        for (HttpStatus refused : List.of(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND)) {
            server.expect(requestTo(url)).andRespond(withStatus(refused).body("{\"detail\":\"refused\"}"));
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            CallResult result = client.execute(PROFILE, DEMO, "mcp__demo__write_note", "{}");
            assertThat(result.error()).as("%d번째 응답", attempt + 1).isEqualTo(ConnectorCallError.UNAVAILABLE);
        }
        server.verify();
    }


    @DisplayName("저장한 인자가 JSON object 가 아니면 보내지 않고 invalid_input 실패 결과다")
    @Test
    void executeDoesNotSendArgumentsThatAreNotAnObject() {
        assertThat(client.execute(PROFILE, DEMO, "mcp__demo__write_note", "[1]").error())
            .isEqualTo(ConnectorCallError.INVALID_INPUT);
        assertThat(client.execute(PROFILE, DEMO, "mcp__demo__write_note", "not json")
            .error())
                .isEqualTo(ConnectorCallError.INVALID_INPUT);
        // 대역 서버에 기대한 요청이 없다. 요청이 나갔으면 대역이 실패시킨다.
        server.verify();
    }


    @DisplayName("도구 호출의 모양이 틀린 응답과 거절 응답은 값을 싣지 않은 예외로 끝난다")
    @Test
    void callRejectsMalformedAndRefusedResponsesWithoutLeakingValues() {
        String url = BASE + "/api/connectors/demo-notes/call";
        server.expect(requestTo(url)).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess("{\"ok\":false,\"error\":\"something_else\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess("{\"result\":{\"scopes\":[]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(TOKEN).contentType(MediaType.TEXT_PLAIN));

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(() -> client.call(DEMO, "list_scopes", Map.of("token", TOKEN), null))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(error -> {
                    assertThat(error.getCause()).isNull();
                    assertThat(error.toString()).doesNotContain(TOKEN);
                });
        }
        server.verify();
    }


    @DisplayName("환경 변수 쓰기와 지우기는 profile 범위의 JSON 으로 보내고 재시작 필요 여부를 유지한다")
    @Test
    void envWritesAndDeletesUseProfileScopedJsonAndPreserveRestart() {
        server.expect(requestTo(BASE + "/api/env"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(
                content().json("{\"profile\":\"user-demo\",\"key\":\"DEMO_TOKEN\",\"value\":\"test-secret\"}"))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"key\":\"DEMO_TOKEN\",\"restart_required\":true}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/env"))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(content().json("{\"profile\":\"user-demo\",\"key\":\"DEMO_TOKEN\"}"))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"key\":\"DEMO_TOKEN\",\"restart_required\":true}",
                MediaType.APPLICATION_JSON));
        assertThat(client.putEnv(PROFILE, "DEMO_TOKEN", "test-secret")).isTrue();
        assertThat(client.deleteEnv(PROFILE, "DEMO_TOKEN")).isTrue();
        server.verify();
    }


    @DisplayName("없는 환경 변수를 지우면 오류 본문이 있어도 실패하지 않고 지운 것이 없다고 답한다")
    @Test
    void missingEnvIsIdempotentEvenWithAnErrorBody() {
        server.expect(requestTo(BASE + "/api/env"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).body("missing key").contentType(MediaType.TEXT_PLAIN));
        assertThat(client.deleteEnv(PROFILE, "DEMO_SCOPE")).isFalse();
        server.verify();
    }


    @DisplayName("모양이 맞는 목록에 받은 커넥터가 없으면 설치되지 않은 것으로 읽는다")
    @Test
    void readsConnectorMissingFromWellFormedListAsNotInstalled() {
        String url = BASE + "/api/connectors?profile=user-demo";
        server.expect(requestTo(url))
            .andRespond(withSuccess("{\"profile\":\"user-demo\",\"connectors\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"connectors\":["
                    + "{\"plugin\":\"other\",\"enabled\":true,\"configured\":true}]}",
                MediaType.APPLICATION_JSON));

        for (int attempt = 0; attempt < 2; attempt++) {
            var state = client.readConnector(PROFILE, DEMO);
            assertThat(state.profile()).isEqualTo(PROFILE);
            assertThat(state.enabled()).isFalse();
            assertThat(state.configured()).isFalse();
        }
        server.verify();
    }


    @DisplayName("설치 목록의 모양이 틀리면 설치되지 않은 것으로 읽지 않고 거절한다")
    @Test
    void rejectsMalformedConnectorListInsteadOfReadingAsNotInstalled() {
        String url = BASE + "/api/connectors?profile=user-demo";
        server.expect(requestTo(url))
            .andRespond(withSuccess("{\"profile\":\"user-demo\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess("{\"profile\":\"user-demo\",\"connectors\":{}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(
                withSuccess("{\"profile\":\"another-user\",\"connectors\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"connectors\":[{\"plugin\":\"demo-notes\",\"enabled\":true}]}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> client.readConnector(PROFILE, DEMO)).isInstanceOf(IllegalStateException.class);
        }
        server.verify();
    }


    @DisplayName("본문이 빈 성공 응답과 다른 profile 의 응답을 거절한다")
    @Test
    void rejectsEmptySuccessAndWrongProfileResponses() {
        server.expect(requestTo(BASE + "/api/env")).andRespond(withSuccess());
        server.expect(requestTo(BASE + "/api/env"))
            .andRespond(withSuccess(
                "{\"profile\":\"another-user\",\"key\":\"DEMO_TOKEN\",\"restart_required\":false}",
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.deleteEnv(PROFILE, "DEMO_TOKEN")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.putEnv(PROFILE, "DEMO_TOKEN", "test-secret"))
            .isInstanceOf(IllegalStateException.class);
        server.verify();
    }


    @DisplayName("받은 이름의 서버를 점검하고 도구 이름이 잘못되면 거절한다")
    @Test
    void probesGivenServerAndRejectsMalformedToolNames() {
        String url = BASE + "/api/mcp/servers/demo/test?profile=user-demo";
        server.expect(requestTo(url))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"ok\":true,\"tools\":[{\"name\":\"list_scopes\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url))
            .andRespond(withSuccess("{\"ok\":true,\"tools\":[{\"name\":\"\"}]}", MediaType.APPLICATION_JSON));
        assertThat(client.probe(PROFILE, "demo").tools()).containsExactly("list_scopes");
        assertThatThrownBy(() -> client.probe(PROFILE, "demo")).isInstanceOf(IllegalStateException.class);
        server.verify();
    }


    @DisplayName("원격이 돌려준 비밀값을 예외와 원인에서 버린다")
    @Test
    void discardsRemoteSecretFromExceptionAndCause() {
        server.expect(requestTo(BASE + "/api/env"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("test-secret-echo")
                .contentType(MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> client.putEnv(PROFILE, "DEMO_TOKEN", "test-secret-echo"))
            .isInstanceOf(IllegalStateException.class)
            .satisfies(error -> {
                assertThat(error.getCause()).isNull();
                assertThat(error.toString()).doesNotContain("test-secret-echo");
            });
        server.verify();
    }


    @DisplayName("카탈로그의 skills 를 읽고, 칸이 없는 옛 카탈로그는 빈 목록이다")
    @Test
    void readsSkillNamesAndDefaultsToEmpty() {
        String tail = "\"mcp_server\":\"demo\"";
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(
                CATALOG.replace(tail, tail + ",\"skills\":[\"demo-notes-guide\"]"),
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors/catalog"))
            .andRespond(withSuccess(CATALOG, MediaType.APPLICATION_JSON));

        assertThat(client.readCatalog().get(0).skills()).containsExactly("demo-notes-guide");
        assertThat(client.readCatalog().get(0).skills()).isEmpty();
        server.verify();
    }


    @DisplayName("보관 파일의 쓰기, 지우기, 옮기기는 정해진 본문을 보내고 답을 읽는다")
    @Test
    void writesAndDeletesVaultWithDocumentedBodies() {
        server.expect(requestTo(BASE + "/api/connector-vault"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header("Authorization", "Bearer test-dashboard-token"))
            .andExpect(content()
                .json(
                    "{\"vault\":\"c7\",\"connector\":\"demo-notes\",\"values\":{\"token\":\"" + TOKEN
                        + "\"}}",
                    JsonCompareMode.STRICT))
            .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connector-vault"))
            .andExpect(method(HttpMethod.DELETE))
            .andExpect(content().json("{\"vault\":\"c7\"}", JsonCompareMode.STRICT))
            .andRespond(withSuccess("{\"changed\":false}", MediaType.APPLICATION_JSON));

        client.putVault("c7", DEMO, Map.of("token", TOKEN));
        assertThat(client.deleteVault("c7")).isFalse();
        server.verify();
    }


    @DisplayName("보관 파일 경로의 거절과 모양이 틀린 답은 원문 없는 예외다")
    @Test
    void vaultRefusalsAndMalformedAnswersFailWithoutLeakingValues() {
        server.expect(requestTo(BASE + "/api/connector-vault"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(TOKEN).contentType(MediaType.TEXT_PLAIN));
        server.expect(requestTo(BASE + "/api/connector-vault"))
            .andRespond(withSuccess("{\"ok\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connector-vault"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        List<Runnable> calls = List.of(
            () -> client.putVault("c7", DEMO, Map.of("token", TOKEN)),
            () -> client.putVault("c7", DEMO, Map.of("token", TOKEN)),
            () -> client.deleteVault("c7"));
        for (Runnable call : calls) {
            assertThatThrownBy(call::run)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(error -> {
                    assertThat(error.getCause()).isNull();
                    assertThat(error.toString()).doesNotContain(TOKEN);
                });
        }
        server.verify();
    }


    @DisplayName("보관 파일로 부르는 도구 호출은 값 대신 보관 파일 이름을 보낸다")
    @Test
    void callWithVaultSendsVaultNameInsteadOfValues() {
        server.expect(requestTo(BASE + "/api/connectors/demo-notes/call"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"tool\":\"list_scopes\",\"vault\":\"c7\"}", JsonCompareMode.STRICT))
            .andRespond(withSuccess("{\"ok\":true,\"result\":{\"scopes\":[]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors/demo-notes/call"))
            .andRespond(
                withSuccess("{\"ok\":false,\"error\":\"credential_rejected\"}", MediaType.APPLICATION_JSON));

        assertThat(client.callWithVault(DEMO, "list_scopes", "c7", null).ok()).isTrue();
        assertThat(client.callWithVault(DEMO, "list_scopes", "c7", null).error())
            .isEqualTo(ConnectorCallError.CREDENTIAL_REJECTED);
        server.verify();
    }


    @DisplayName("바인딩 설치는 bind 칸에 보관 파일 이름과 sandbox_owner 를 싣고 떼기는 bind 칸 없이 끈다")
    @Test
    void bindsWithVaultAndUnbindsWithoutBindField() {
        server.expect(requestTo(BASE + "/api/connectors"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content()
                .json(
                    "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":true,"
                        + "\"bind\":{\"vault\":\"c7\"},\"sandbox_owner\":\"u1\"}",
                    JsonCompareMode.STRICT))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":true,"
                    + "\"restart_required\":true,\"plugin_updated\":false}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content()
                .json(
                    "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":false}",
                    JsonCompareMode.STRICT))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":false,"
                    + "\"restart_required\":false}",
                MediaType.APPLICATION_JSON));

        assertThat(client.bindConnector(PROFILE, DEMO, "c7", "u1", null)).isEqualTo(new InstallResult(true, false));
        assertThat(client.unbindConnector(PROFILE, DEMO)).isEqualTo(new InstallResult(false, false));
        server.verify();
    }


    @DisplayName("중계 주소를 주면 도구 호출과 바인딩 설치의 본문에 owner_browser 를 싣는다. 빈 값도 그대로 싣는다")
    @Test
    void sendsOwnerBrowserOnlyWhenGiven() {
        String binding = "http://cp.example.test/internal/browser-gateway/b7." + "a".repeat(64);
        String call = "http://cp.example.test/internal/browser-gateway/u1.1700000300." + "c".repeat(64);
        String ok = "{\"ok\":true,\"result\":{\"scopes\":[]}}";
        server.expect(requestTo(BASE + "/api/connectors/demo-notes/call"))
            .andExpect(content()
                .json(
                    "{\"tool\":\"list_scopes\",\"values\":{},\"owner_browser\":\"" + call + "\"}",
                    JsonCompareMode.STRICT))
            .andRespond(withSuccess(ok, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors/demo-notes/call"))
            .andExpect(content()
                .json(
                    "{\"tool\":\"list_scopes\",\"vault\":\"c7\",\"owner_browser\":\"" + call + "\"}",
                    JsonCompareMode.STRICT))
            .andRespond(withSuccess(ok, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors"))
            .andExpect(content()
                .json(
                    "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":true,"
                        + "\"bind\":{\"vault\":\"c7\"},\"sandbox_owner\":\"u1\","
                        + "\"owner_browser\":\"" + binding + "\"}",
                    JsonCompareMode.STRICT))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":true,"
                    + "\"restart_required\":false}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors"))
            .andExpect(content()
                .json(
                    "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":true,"
                        + "\"bind\":{\"vault\":\"c7\"},\"sandbox_owner\":\"u1\","
                        + "\"owner_browser\":\"\"}",
                    JsonCompareMode.STRICT))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":true,"
                    + "\"restart_required\":false}",
                MediaType.APPLICATION_JSON));

        assertThat(client.call(DEMO, "list_scopes", Map.of(), call).ok()).isTrue();
        assertThat(client.callWithVault(DEMO, "list_scopes", "c7", call).ok()).isTrue();
        assertThat(client.bindConnector(PROFILE, DEMO, "c7", "u1", binding)).isEqualTo(new InstallResult(false, false));
        assertThat(client.bindConnector(PROFILE, DEMO, "c7", "u1", "")).isEqualTo(new InstallResult(false, false));
        server.verify();
    }


    @DisplayName("바인딩 설치는 보내기 전에 그 주인의 첨부 디렉터리를 만든다")
    @Test
    void bindCreatesTheOwnersAttachmentDirectoryFirst() {
        server.expect(requestTo(BASE + "/api/connectors"))
            .andExpect(content().json("{\"enabled\":true,\"sandbox_owner\":\"u3\"}"))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":true,"
                    + "\"restart_required\":false}",
                MediaType.APPLICATION_JSON));

        assertThat(client.bindConnector(PROFILE, DEMO, "c7", "u3", null)).isEqualTo(new InstallResult(false, false));
        assertThat(attachmentRoot.resolve("users").resolve(SandboxAttachmentDirectory.key("u3")))
            .isDirectory();
        server.verify();
    }


    @DisplayName("바인딩 설치는 첨부 디렉터리를 만들지 못해도 요청을 보내고, 대시보드의 409 를 설치 충돌로 돌려준다")
    @Test
    void bindSendsEvenWhenTheAttachmentDirectoryCannotBeMade() throws Exception {
        Path users = Files.createDirectory(attachmentRoot.resolve("users"));
        Path other = Files.createDirectory(users.resolve(SandboxAttachmentDirectory.key("u4")));
        Files.createSymbolicLink(users.resolve(SandboxAttachmentDirectory.key("u5")), other);
        // 첨부를 선언하지 않은 커넥터는 대시보드가 디렉터리를 보지 않으므로 설치된다.
        server.expect(requestTo(BASE + "/api/connectors"))
            .andExpect(content().json("{\"enabled\":true,\"sandbox_owner\":\"u5\"}"))
            .andRespond(withSuccess(
                "{\"profile\":\"user-demo\",\"plugin\":\"demo-notes\",\"enabled\":true,"
                    + "\"restart_required\":true}",
                MediaType.APPLICATION_JSON));
        // 첨부를 선언한 커넥터는 대시보드가 링크를 보고 409 로 거절한다.
        server.expect(requestTo(BASE + "/api/connectors"))
            .andExpect(content().json("{\"enabled\":true,\"sandbox_owner\":\"u5\"}"))
            .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThat(client.bindConnector(PROFILE, DEMO, "c7", "u5", null)).isEqualTo(new InstallResult(true, false));
        assertThatThrownBy(() -> client.bindConnector(PROFILE, DEMO, "c7", "u5", null))
            .isInstanceOf(ConnectorInstallConflict.class);
        assertThat(Files.isSymbolicLink(users.resolve(SandboxAttachmentDirectory.key("u5"))))
            .isTrue();
        server.verify();
    }


    @DisplayName("바인딩 설치의 409 와 401 은 각자의 예외이고 응답 본문을 싣지 않는다")
    @Test
    void mapsBindConflictAndProfileRefusalWithoutBody() {
        String detail = "{\"detail\":\"conflicts with MY_SECRET_ENV\"}";
        server.expect(requestTo(BASE + "/api/connectors"))
            .andRespond(withStatus(HttpStatus.CONFLICT).body(detail).contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body(detail).contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors"))
            .andRespond(withStatus(HttpStatus.CONFLICT).body(detail).contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(detail).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.bindConnector(PROFILE, DEMO, "c7", "u1", null))
            .isInstanceOfSatisfying(ConnectorInstallConflict.class, ex -> assertNoDetail(ex));
        assertThatThrownBy(() -> client.bindConnector(PROFILE, DEMO, "c7", "u1", null))
            .isInstanceOfSatisfying(ConnectorProfileRejected.class, ex -> assertNoDetail(ex));
        assertThatThrownBy(() -> client.unbindConnector(PROFILE, DEMO))
            .isInstanceOfSatisfying(ConnectorInstallConflict.class, ex -> assertNoDetail(ex));
        assertThatThrownBy(() -> client.bindConnector(PROFILE, DEMO, "c7", "u1", null))
            .isInstanceOfSatisfying(IllegalStateException.class, ex -> assertNoDetail(ex));
        server.verify();
    }


    @DisplayName("바인딩 설치의 409 는 본문 code 가 sandbox_unavailable 일 때만 실행 공간 거절이고 그 밖은 설치 충돌이다")
    @Test
    void mapsSandboxUnavailableConflictByBodyCode() {
        server.expect(requestTo(BASE + "/api/connectors"))
            .andRespond(withStatus(HttpStatus.CONFLICT)
                .body("{\"detail\":\"x\",\"code\":\"sandbox_unavailable\"}")
                .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors"))
            .andRespond(withStatus(HttpStatus.CONFLICT)
                .body("{\"detail\":\"x\"}")
                .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.bindConnector(PROFILE, DEMO, "c7", "u1", null))
            .isInstanceOfSatisfying(ConnectorSandboxUnavailable.class, ex -> assertNoDetail(ex));
        assertThatThrownBy(() -> client.bindConnector(PROFILE, DEMO, "c7", "u1", null))
            .isInstanceOfSatisfying(ConnectorInstallConflict.class, ex -> assertNoDetail(ex));
        server.verify();
    }


    @DisplayName("설치 목록의 mode 가 bind 일 때 읽는다")
    @Test
    void readsBindInstallMode() {
        String url = BASE + "/api/connectors?profile=user-demo";
        String head = "{\"profile\":\"user-demo\",\"connectors\":[{\"plugin\":\"demo-notes\",\"enabled\":true,"
            + "\"configured\":true";
        server.expect(requestTo(url))
            .andRespond(withSuccess(head + ",\"mode\":\"bind\"}]}", MediaType.APPLICATION_JSON));
        assertThat(client.readConnector(PROFILE, DEMO).mode()).isEqualTo(HermesConnectorClient.MODE_BIND);
        server.verify();
    }


    private static void assertNoDetail(RuntimeException ex) {
        assertThat(ex.getMessage()).isNull();
        assertThat(ex.getCause()).isNull();
        assertThat(ex.toString()).doesNotContain("MY_SECRET_ENV");
    }
}
