package com.bifos.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.hermes.infra.HttpHermesGatewayClient;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 대시보드 plugin 의 {@code GET /api/gateway} 답을 기동 시각으로 읽는다(ADR-20261120 / connector-auto-apply). */
class HttpHermesGatewayClientTest {
    private static final String BASE = "https://dashboard.example.test";
    private static final String URL = BASE + "/api/gateway";
    private HttpHermesGatewayClient client;
    private MockRestServiceServer server;


    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpHermesGatewayClient(
            new HermesProperties("unused", BASE + "/", "test-dashboard-token", BASE, null, null, null, null));
        ReflectionTestUtils.setField(client, "client", builder.build());
    }


    @Test
    @DisplayName("기동 시각을 epoch 초로 받아 서비스 토큰으로 부른다")
    void readsStartedAt() {
        server.expect(requestTo(URL))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer test-dashboard-token"))
            .andRespond(withSuccess("{\"started_at\":1763600000}", MediaType.APPLICATION_JSON));

        assertThat(client.startedAt()).contains(Instant.ofEpochSecond(1_763_600_000L));
        server.verify();
    }


    @Test
    @DisplayName("gateway 가 떠 있지 않아 null 이면 비어 있다")
    void nullIsUnknown() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"started_at\":null}", MediaType.APPLICATION_JSON));

        assertThat(client.startedAt()).isEmpty();
    }


    @Test
    @DisplayName("옛 plugin 이 404 로 답하면 예외 없이 비어 있다")
    void notFoundIsUnknown() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.startedAt()).isEmpty();
    }


    @Test
    @DisplayName("기동 시각이 정수가 아니거나 0 이하면 비어 있다")
    void malformedIsUnknown() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"started_at\":\"1763600000\"}", MediaType.APPLICATION_JSON));
        assertThat(client.startedAt()).isEmpty();
        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"started_at\":0}", MediaType.APPLICATION_JSON));
        assertThat(client.startedAt()).isEmpty();
        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"started_at\":1.5}", MediaType.APPLICATION_JSON));
        assertThat(client.startedAt()).isEmpty();
    }


    @Test
    @DisplayName("대시보드가 500 으로 답해도 예외 없이 비어 있다")
    void serverErrorIsUnknown() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.startedAt()).isEmpty();
    }
}
