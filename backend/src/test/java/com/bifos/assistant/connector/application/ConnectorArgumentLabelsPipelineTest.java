package com.bifos.assistant.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.connection.domain.type.ToolRisk;
import com.bifos.assistant.connector.presentation.ConnectorActionDtos;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.hermes.infra.HttpHermesConnectorClient;
import com.bifos.assistant.hermes.infra.SandboxAttachmentDirectory;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class ConnectorArgumentLabelsPipelineTest {
    @TempDir
    Path attachments;


    @Test
    @DisplayName("실제 producer의 가려진 원래 키는 카탈로그를 읽은 뒤 승인 DTO 어디에도 내지 않는다")
    void removesMaskedProducerKeysFromActionDto() throws Exception {
        String base = "https://dashboard.example.test";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpHermesConnectorClient client = new HttpHermesConnectorClient(
            new HermesProperties("unused", base, "test-dashboard-token", base, null, null, null, null),
            new SandboxAttachmentDirectory(attachments.toString()));
        ReflectionTestUtils.setField(client, "client", builder.build());
        server.expect(requestTo(base + "/api/connectors/catalog")).andRespond(withSuccess(
            Files.readString(Path.of("../test/resources/connector-argument-masked-catalog.json")),
            MediaType.APPLICATION_JSON));
        ConnectorManifest manifest = client.readCatalog().getFirst();
        String maskedKey = "ghp_synthetic_review_key";
        assertThat(manifest.tools().getFirst().argLabels()).containsKey(maskedKey);
        ConnectorAction action = mock(ConnectorAction.class);
        String original = "{\"scope\":\"합성 범위\",\"" + maskedKey + "\":\"합성 값\"}";
        when(action.publicId()).thenReturn(UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100"));
        when(action.connectorId()).thenReturn("demo-notes");
        when(action.toolName()).thenReturn("write_note");
        when(action.argsJson()).thenReturn(original);
        when(action.status()).thenReturn(ActionStatus.PENDING);
        when(action.risk()).thenReturn(ToolRisk.WRITE);
        when(action.createdAt()).thenReturn(Instant.parse("2026-10-12T00:00:00Z"));
        ConnectorActionDtos.ActionView dto = ConnectorActionDtos.ActionView.from(
            ConnectorActionDetails.view(action, Optional.of(manifest)));
        assertThat(dto.argsJson()).contains("[가림]").doesNotContain(maskedKey);
        assertThat(dto.hiddenArgs()).isTrue();
        assertThat(dto.grantAllowed()).isFalse();
        assertThat(dto.argLabels().keySet()).containsExactly("scope");
        JsonMapper mapper = JsonMapper.builder().build();
        String response = mapper.writeValueAsString(dto);
        assertThat(response.contains(maskedKey)).isFalse();
        assertThat(action.argsJson()).isEqualTo(original);
        Path fixture = Path.of("../test/resources/connector-argument-masked-action.json");
        if ("true".equals(System.getenv("FOS_EXPORT_ARGUMENT_DTO"))) {
            Files.writeString(fixture, response + "\n");
        }
        assertThat(mapper.readTree(response)).isEqualTo(mapper.readTree(Files.readString(fixture)));
        server.verify();
    }


    @Test
    @DisplayName("실제 Python 카탈로그에서 Java 승인 응답까지 Gmail과 네이버 표시 이름을 전달한다")
    void carriesProducerLabelsThroughActionDto() throws Exception {
        String base = "https://dashboard.example.test";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpHermesConnectorClient client = new HttpHermesConnectorClient(
            new HermesProperties("unused", base, "test-dashboard-token", base, null, null, null, null),
            new SandboxAttachmentDirectory(attachments.toString()));
        ReflectionTestUtils.setField(client, "client", builder.build());
        server.expect(requestTo(base + "/api/connectors/catalog")).andRespond(withSuccess(
            Files.readString(Path.of("../test/resources/connector-argument-catalog.json")), MediaType.APPLICATION_JSON));
        for (ConnectorManifest manifest : client.readCatalog()) {
            ConnectorAction action = mock(ConnectorAction.class);
            when(action.toolName()).thenReturn(manifest.id().equals("gmail") ? "send_message" : "save_draft");
            when(action.argsJson()).thenReturn("{\"body\":\"합성 본문\",\"unknown\":\"보존\"}");
            when(action.status()).thenReturn(ActionStatus.PENDING);
            ConnectorActionDtos.ActionView view = ConnectorActionDtos.ActionView.from(
                ConnectorActionDetails.view(action, Optional.of(manifest)));
            assertThat(view.argLabels()).containsEntry("body", "본문");
            assertThat(view.argsJson()).isEqualTo(action.argsJson());
            assertThat(JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(view))
                .get("argLabels").get("body").asString()).isEqualTo("본문");
            when(action.toolName()).thenReturn("unknown_tool");
            assertThat(ConnectorActionDetails.view(action, Optional.of(manifest)).argLabels()).isEmpty();
            assertThat(ConnectorActionDetails.view(action, Optional.empty()).argLabels()).isEmpty();
        }
        server.verify();
    }
}
