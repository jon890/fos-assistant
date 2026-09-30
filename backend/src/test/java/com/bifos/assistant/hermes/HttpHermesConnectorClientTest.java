package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpHermesConnectorClientTest {
    private static final String BASE = "https://dashboard.example.test";
    private HttpHermesConnectorClient client;
    private MockRestServiceServer server;

    @BeforeEach void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpHermesConnectorClient(new HermesProperties("unused", BASE, "test-dashboard-token", BASE, null, null, null, null));
        ReflectionTestUtils.setField(client, "client", builder.build());
    }

    @Test void installsOnlyTheNamedPluginAndReadsConfiguredState() {
        server.expect(requestTo(BASE + "/api/connectors")).andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer test-dashboard-token"))
                .andExpect(content().json("{\"profile\":\"user-accountbook\",\"plugin\":\"fos-accountbook\",\"enabled\":true}"))
                .andRespond(withSuccess("{\"profile\":\"user-accountbook\",\"plugin\":\"fos-accountbook\",\"enabled\":true,\"restart_required\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/connectors?profile=user-accountbook"))
                .andRespond(withSuccess("{\"profile\":\"user-accountbook\",\"connectors\":[{\"plugin\":\"fos-accountbook\",\"enabled\":true,\"configured\":true}]}", MediaType.APPLICATION_JSON));
        assertThat(client.putConnector("user-accountbook", true)).isTrue();
        var state = client.readConnector("user-accountbook");
        assertThat(state.enabled()).isTrue();
        assertThat(state.configured()).isTrue();
        server.verify();
    }

    @Test void envWritesAndDeletesUseProfileScopedJsonAndPreserveRestart() {
        server.expect(requestTo(BASE + "/api/env")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"profile\":\"user-accountbook\",\"key\":\"ACCOUNTBOOK_API_TOKEN\",\"value\":\"test-secret\"}"))
                .andRespond(withSuccess("{\"profile\":\"user-accountbook\",\"key\":\"ACCOUNTBOOK_API_TOKEN\",\"restart_required\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/env")).andExpect(method(HttpMethod.DELETE))
                .andExpect(content().json("{\"profile\":\"user-accountbook\",\"key\":\"ACCOUNTBOOK_API_TOKEN\"}"))
                .andRespond(withSuccess("{\"profile\":\"user-accountbook\",\"key\":\"ACCOUNTBOOK_API_TOKEN\",\"restart_required\":true}", MediaType.APPLICATION_JSON));
        assertThat(client.putEnv("user-accountbook", "ACCOUNTBOOK_API_TOKEN", "test-secret")).isTrue();
        assertThat(client.deleteEnv("user-accountbook", "ACCOUNTBOOK_API_TOKEN")).isTrue();
        server.verify();
    }

    @Test void missingEnvIsIdempotentEvenWithAnErrorBody() {
        server.expect(requestTo(BASE + "/api/env")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .body("missing key").contentType(MediaType.TEXT_PLAIN));
        assertThat(client.deleteEnv("user-accountbook", "ACCOUNTBOOK_FAMILY_UUID")).isFalse();
        server.verify();
    }

    @Test void rejectsMissingKnownPluginInsteadOfConfirmingDisconnection() {
        server.expect(requestTo(BASE + "/api/connectors?profile=user-accountbook"))
                .andRespond(withSuccess("{\"profile\":\"user-accountbook\",\"connectors\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.readConnector("user-accountbook")).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test void rejectsEmptySuccessAndWrongProfileResponses() {
        server.expect(requestTo(BASE + "/api/env")).andRespond(withSuccess());
        server.expect(requestTo(BASE + "/api/env")).andRespond(withSuccess("{\"profile\":\"another-user\",\"key\":\"ACCOUNTBOOK_API_TOKEN\",\"restart_required\":false}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.deleteEnv("user-accountbook", "ACCOUNTBOOK_API_TOKEN"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.putEnv("user-accountbook", "ACCOUNTBOOK_API_TOKEN", "test-secret"))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test void probesKnownServerAndRejectsMalformedToolNames() {
        String url = BASE + "/api/mcp/servers/accountbook/test?profile=user-accountbook";
        server.expect(requestTo(url)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true,\"tools\":[{\"name\":\"expense_list\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(url)).andRespond(withSuccess("{\"ok\":true,\"tools\":[{\"name\":\"\"}]}", MediaType.APPLICATION_JSON));
        assertThat(client.probeAccountbook("user-accountbook").tools()).containsExactly("expense_list");
        assertThatThrownBy(() -> client.probeAccountbook("user-accountbook")).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test void discardsRemoteSecretFromExceptionAndCause() {
        server.expect(requestTo(BASE + "/api/env")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("test-secret-echo").contentType(MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> client.putEnv("user-accountbook", "ACCOUNTBOOK_API_TOKEN", "test-secret-echo"))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(error -> {
                    assertThat(error.getCause()).isNull();
                    assertThat(error.toString()).doesNotContain("test-secret-echo");
                });
        server.verify();
    }
}
