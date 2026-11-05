package com.bifos.assistant.hermes.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorEventListing;
import com.bifos.assistant.hermes.application.dto.ConnectorEventWatch;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 사건 감지 목록 조회 응답과 카탈로그 선언을 읽는 규칙이다(ADR-20261105 / event-watch). 모든 값은 합성이다. */
class HttpHermesEventFeedClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();


    @Test
    @DisplayName("항목은 식별자, 받은 시각, 라벨만 읽고 다른 칸과 모양이 틀린 식별자는 버린다")
    void readsOnlyIdentifierTimeAndLabels() {
        ConnectorEventListing listing = HttpHermesEventFeedClient.read(JSON.readTree("""
            {"ok": true, "truncated": true, "items": [
              {"id": "m1", "received_at": "2026-11-05T00:00:00Z", "labels": ["INBOX", 3], "subject": "합성 제목"},
              {"id": "bad id", "received_at": null, "labels": []},
              {"id": "m2", "received_at": "어제", "labels": "INBOX"}
            ]}"""));

        assertThat(listing.ok()).isTrue();
        assertThat(listing.truncated()).isTrue();
        assertThat(listing.items()).containsExactly(
            new ConnectorEventListing.Item("m1", Instant.parse("2026-11-05T00:00:00Z"), List.of("INBOX")),
            new ConnectorEventListing.Item("m2", null, List.of()));
    }


    @Test
    @DisplayName("실패 답은 공통 어휘로 읽고 모르는 어휘는 unavailable 이며, ok 가 없는 답은 틀린 응답이다")
    void readsFailureWordsAndRejectsMalformedAnswers() {
        assertThat(HttpHermesEventFeedClient.read(JSON.readTree("{\"ok\": false, \"error\": \"credential_rejected\"}")).error())
            .isEqualTo(ConnectorCallError.CREDENTIAL_REJECTED);
        assertThat(HttpHermesEventFeedClient.read(JSON.readTree("{\"ok\": false, \"error\": \"odd\"}")).error())
            .isEqualTo(ConnectorCallError.UNAVAILABLE);
        assertThatThrownBy(() -> HttpHermesEventFeedClient.read(JSON.readTree("{\"items\": []}")))
            .isInstanceOf(IllegalStateException.class);
    }


    @Test
    @DisplayName("카탈로그의 사건 감지 선언은 https 링크와 식별자 자리가 있을 때만 읽고 틀리면 선언이 없는 것으로 둔다")
    void readsEventWatchDeclaration() {
        ConnectorEventWatch watch = ConnectorEventWatch.read(JSON.readTree(
            "{\"tool\": \"list_notes\", \"noun\": \"메모\", \"link\": \"https://notes.example.test/n/{id}\"}"));
        assertThat(watch).isEqualTo(new ConnectorEventWatch("list_notes", "메모", "https://notes.example.test/n/{id}"));
        assertThat(watch.linkOf("a1")).isEqualTo("https://notes.example.test/n/a1");
        assertThat(ConnectorEventWatch.read(JSON.readTree(
            "{\"tool\": \"list_notes\", \"noun\": \"메모\", \"link\": \"http://notes.example.test/{id}\"}"))).isNull();
        assertThat(ConnectorEventWatch.read(null)).isNull();
    }
}
