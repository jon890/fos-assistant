package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.bifos.assistant.connector.application.AccountbookProperties;
import com.bifos.assistant.connector.infra.HttpAccountbookTokenVerifier;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpAccountbookTokenVerifierTest {
    private static final String BASE = "https://accountbook.example.test/api/v1";
    private static final UUID FAMILY = UUID.fromString("e5b60d52-bc21-4781-b230-df0ee8e11a65");
    private HttpAccountbookTokenVerifier verifier;
    private MockRestServiceServer server;

    @BeforeEach void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        verifier = new HttpAccountbookTokenVerifier(new AccountbookProperties(BASE, null, null));
        ReflectionTestUtils.setField(verifier, "client", builder.build());
    }

    @Test void readsTheRealSuccessEnvelopeAndAuthorizesSelectedFamily() {
        server.expect(requestTo(BASE + "/families")).andExpect(header("Authorization", "Bearer test-secret"))
                .andRespond(withSuccess("{\"success\":true,\"data\":[{\"uuid\":\"" + FAMILY + "\",\"name\":\"함께 쓰는 가계부\"}]}", MediaType.APPLICATION_JSON));
        verifier.verify("test-secret", FAMILY);
        server.verify();
    }

    @Test void rejectsAValidTokenForAnUnrelatedFamily() {
        server.expect(requestTo(BASE + "/families")).andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        assertCode(() -> verifier.verify("test-secret", FAMILY), ErrorCode.ACCOUNTBOOK_FAMILY_FORBIDDEN);
        server.verify();
    }

    @Test void rejectsMalformedResponseEvenWithoutSelectedFamily() {
        server.expect(requestTo(BASE + "/families")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertCode(() -> verifier.verify("test-secret", null), ErrorCode.ACCOUNTBOOK_UNAVAILABLE);
        server.verify();
    }

    @Test void distinguishesAuthenticationFromAvailabilityWithoutRemoteSecrets() {
        server.expect(requestTo(BASE + "/families")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("test-secret-echo").contentType(MediaType.TEXT_PLAIN));
        server.expect(requestTo(BASE + "/families")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body("test-secret-echo").contentType(MediaType.TEXT_PLAIN));
        assertCode(() -> verifier.verify("test-secret", null), ErrorCode.ACCOUNTBOOK_TOKEN_REJECTED);
        assertCode(() -> verifier.verify("test-secret", null), ErrorCode.ACCOUNTBOOK_UNAVAILABLE);
        server.verify();
    }

    @Test void redirectsAreUnavailableAndDoNotFollowTheirLocation() {
        server.expect(requestTo(BASE + "/families")).andRespond(withStatus(HttpStatus.FOUND)
                .location(java.net.URI.create("https://another.example.test/families")));
        assertCode(() -> verifier.verify("test-secret", null), ErrorCode.ACCOUNTBOOK_UNAVAILABLE);
        server.verify();
    }

    @Test void timeoutIsUnavailableWithoutTheRemoteCause() {
        server.expect(requestTo(BASE + "/families")).andRespond(withException(new java.net.SocketTimeoutException("test-secret-echo")));
        assertCode(() -> verifier.verify("test-secret", null), ErrorCode.ACCOUNTBOOK_UNAVAILABLE);
        server.verify();
    }

    @Test void absentOrInsecureAddressDisablesOnlyTheFeature() {
        assertCode(() -> new HttpAccountbookTokenVerifier(new AccountbookProperties("", null, null)).verify("test-secret", null), ErrorCode.ACCOUNTBOOK_UNAVAILABLE);
        assertThat(new AccountbookProperties("http://accountbook.example.test/api/v1", null, null).configured()).isFalse();
        assertThat(new AccountbookProperties("https://127.0.0.1/api/v1", null, null).configured()).isFalse();
        assertThat(new AccountbookProperties("https://[::1]/api/v1", null, null).configured()).isFalse();
    }

    private static void assertCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(error -> {
            assertThat(((ApiException) error).code()).isEqualTo(expected);
            assertThat(error.getCause()).isNull();
            assertThat(error.toString()).doesNotContain("test-secret-echo");
        });
    }
}
