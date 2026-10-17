package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.connector.application.ContentDraftSaveTicket;
import com.bifos.assistant.connector.application.model.ContentDraftSaveTicketPayload;
import com.bifos.assistant.connector.presentation.contentjob.ContentDraftJobAuthorizeDtos.AuthorizeRequest;
import com.bifos.assistant.hermes.config.HermesProperties;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContentDraftSaveTicketTest {
    private static final Instant NOW = Instant.parse("2026-10-12T01:00:00.123456Z");
    private static final Instant UPDATED = Instant.parse("2026-10-11T00:00:00.000001Z");


    @Test
    @DisplayName("발급한 ticket은 같은 칸으로 인증되고 기한은 660초와 원고 기한 가운데 이른 쪽이다")
    void authenticatesIssuedTicketWithBoundedLifetime() {
        var ticket = tickets("token", NOW).issue(payload(), NOW.plusSeconds(3600));
        var payload = tickets("token", NOW.plusSeconds(10)).authenticate(ticket);
        assertThat(payload.jobId()).isEqualTo(payload().jobId());
        assertThat(payload.bindingUpdatedAt()).isEqualTo(UPDATED);
        assertThat(payload.issuedAt()).isEqualTo(NOW);
        assertThat(payload.expiresAt()).isEqualTo(NOW.plusSeconds(660));
        var shorter = tickets("token", NOW).issue(payload(), NOW.plusSeconds(30));
        assertThat(tickets("token", NOW).authenticate(shorter).expiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(new String(Base64.getUrlDecoder().decode(ticket.split("\\.")[0]), StandardCharsets.UTF_8))
            .startsWith("{\"v\":1,\"purpose\":\"content-snapshot-save\",\"jobId\":")
            .contains("\"ownerUserId\":\"7\"", "\"bindingUpdatedAt\":\"2026-10-11T00:00:00.000001Z\"");
    }


    @Test
    @DisplayName("만료·다른 key·변조·금융 domain 서명·사용자 JWT 모양은 401이다")
    void rejectsExpiredForeignTamperedAndFinancialTickets() {
        var ticket = tickets("token", NOW).issue(payload(), NOW.plusSeconds(3600));
        unauthenticated(() -> tickets("token", NOW.plusSeconds(660)).authenticate(ticket));
        unauthenticated(() -> tickets("token", NOW.minusSeconds(1)).authenticate(ticket));
        unauthenticated(() -> tickets("other", NOW).authenticate(ticket));
        String body = ticket.split("\\.")[0];
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(
            new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).replace("\"7\"", "\"8\"").getBytes(StandardCharsets.UTF_8));
        unauthenticated(() -> tickets("token", NOW).authenticate(tampered + "." + ticket.split("\\.")[1]));
        unauthenticated(() -> tickets("token", NOW).authenticate(body + "." + financialSignature(body)));
        unauthenticated(() -> tickets("token", NOW).authenticate("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1In0.c2ln"));
        unauthenticated(() -> tickets("token", NOW).authenticate(""));
        unauthenticated(() -> tickets("", NOW).authenticate(ticket));
    }


    @Test
    @DisplayName("authorize 요청의 문자열 표현과 오류에 ticket을 싣지 않는다")
    void redactsTicketFromRequestRepresentation() {
        var ticket = tickets("token", NOW).issue(payload(), NOW.plusSeconds(3600));
        var request = AuthorizeRequest.decode(("{\"ticket\":\"" + ticket + "\",\"stage\":\"photo\",\"position\":3}").getBytes(StandardCharsets.UTF_8));
        assertThat(request.position()).isEqualTo(3);
        assertThat(request.toString()).doesNotContain(ticket);
        for (String raw : new String[] {"{\"ticket\":\"" + ticket + "\",\"stage\":\"save\",\"extra\":1}", "{\"ticket\":1,\"stage\":\"save\"}",
            "{\"ticket\":\"a\",\"stage\":\"photo\",\"position\":\"3\"}", "{\"ticket\":\"a\",\"ticket\":\"b\",\"stage\":\"save\"}", "[]"}) {
            assertThatThrownBy(() -> AuthorizeRequest.decode(raw.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).doesNotContain(ticket);
                });
        }
    }


    private static ContentDraftSaveTicket tickets(String token, Instant now) {
        return new ContentDraftSaveTicket(new HermesProperties(null, "http://127.0.0.1", token, null, null, null, null, null),
            Clock.fixed(now, ZoneOffset.UTC));
    }


    private static ContentDraftSaveTicketPayload payload() {
        return new ContentDraftSaveTicketPayload(UUID.fromString("0190a8a4-0000-7000-8000-000000000001"),
            UUID.fromString("0190a8a4-0000-7000-8000-000000000002"), "a".repeat(64), UUID.fromString("0190a8a4-0000-7000-8000-000000000003"),
            7L, 11L, 13L, 17L, UPDATED, UPDATED, NOW, NOW);
    }


    private static String financialSignature(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("token".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] key = mac.doFinal("fos-approval-signing-key-v1".getBytes(StandardCharsets.US_ASCII));
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("fos-approval-claim-v1" + body).getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }


    private static void unauthenticated(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}
