package com.bifos.assistant.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bifos.assistant.connector.domain.ConnectorAction;
import com.bifos.assistant.connector.domain.type.ActionStatus;
import com.bifos.assistant.connector.presentation.ConnectorActionDtos;
import com.bifos.assistant.hermes.HermesProperties;
import com.bifos.assistant.hermes.HttpHermesConnectorClient;
import com.bifos.assistant.hermes.SandboxAttachmentDirectory;
import com.bifos.assistant.hermes.dto.ConnectorManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
