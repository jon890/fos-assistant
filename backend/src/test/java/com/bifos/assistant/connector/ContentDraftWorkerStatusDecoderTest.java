package com.bifos.assistant.connector;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.bifos.assistant.connector.application.ContentDraftWorkerStatusDecoder;
import com.bifos.assistant.connector.application.model.ContentDraftSaveTicketPayload;
import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.hermes.application.dto.ContentDraftWorkerStamp;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ContentDraftWorkerStatusDecoderTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ContentDraftWorkerStamp stamp = ContentDraftWorkerStatusDecoder.stamp("fixture", new ContentDraftSaveTicketPayload(
        UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), 1L, 2L, 3L, 4L,
        Instant.parse("2026-10-11T00:00:00Z"), Instant.parse("2026-10-11T00:00:00.123400Z"), null, null));


    @Test
    @DisplayName("stamp 시각은 마이크로초가 0이거나 끝자리가 0이어도 여섯 자리를 보존한다")
    void preservesSixDigitsForZeroAndTrailingZeroMicros() {
        assertThat(stamp.bindingUpdatedAt()).isEqualTo("2026-10-11T00:00:00.000000Z");
        assertThat(stamp.connectionUpdatedAt()).isEqualTo("2026-10-11T00:00:00.123400Z");
    }


    @Test
    @DisplayName("worker 소문자 상태를 명시적으로 옮기고 정확한 projection만 받는다")
    void decodesWorkerStatusesExplicitly() {
        for (var status : Map.of("running", ContentDraftJobStatus.SAVING, "succeeded", ContentDraftJobStatus.SUCCEEDED,
            "failed", ContentDraftJobStatus.FAILED, "unknown", ContentDraftJobStatus.UNKNOWN).entrySet()) {
            assertThat(ContentDraftWorkerStatusDecoder.decode(answer().put("status", status.getKey()), stamp, false).status()).isEqualTo(status.getValue());
        }
    }


    @Test
    @DisplayName("잘못된 stamp·단계·대소문자·boolean·수치·본문 혼입을 cause 없는 오류로 거절한다")
    void rejectsStampTypeAndBodyTampering() {
        var changed = answer();
        ((ObjectNode)changed.path("stamp")).put("bindingId", "99");
        reject(changed);
        for (var node : List.of(answer().put("status", "SUCCEEDED"), answer().put("stage", "save_clicking"),
            answer().put("save_clicked", "true"), answer().put("save_clicked", 1), answer().put("saved_before", 3),
            answer().put("saved_before", true), answer().put("error_code", "secret/path"), answer().put("body", "BODY_MARKER"),
            answer().put("ticket", "TICKET_MARKER"), answer().put("finished_at", "invalid"), answer().put("job_id", UUID.randomUUID().toString()))) {
            reject(node);
        }
    }


    @Test
    @DisplayName("error_reason 은 status 응답에서만 선택으로 받고 고정 코드 모양만 허용한다")
    void acceptsOptionalErrorReasonOnlyAsFixedCode() {
        assertThat(ContentDraftWorkerStatusDecoder.decode(answer(), stamp, false).errorReason()).isNull();
        assertThat(ContentDraftWorkerStatusDecoder.decode(answer().put("error_reason", "title_cursor_covered"), stamp, false)
            .errorReason()).isEqualTo("title_cursor_covered");
        for (var bad : List.of("Title Cursor", "secret/path", "", "a".repeat(65))) {
            reject(answer().put("error_reason", bad));
        }
        reject(answer().putNull("error_reason"));
        reject(answer().put("error_reason", 1));
        var start = json.createObjectNode().put("job_id", stamp.jobId().toString()).set("stamp", json.valueToTree(stamp));
        ((ObjectNode)start).put("status", "running").putNull("error_code").put("error_reason", "title_cursor_covered");
        assertThatThrownBy(() -> ContentDraftWorkerStatusDecoder.decode((ObjectNode)start, stamp, true)).isInstanceOf(ApiException.class);
    }


    private ObjectNode answer() {
        return json.createObjectNode().put("job_id", stamp.jobId().toString()).set("stamp", json.valueToTree(stamp))
            .put("status", "running").put("stage", "queued").put("save_clicked", false).putNull("finished_at")
            .putNull("saved_before").putNull("saved_after").putNull("error_code");
    }


    private void reject(ObjectNode node) {
        assertThatThrownBy(() -> ContentDraftWorkerStatusDecoder.decode(node, stamp, false)).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE);
            assertThat(ex.getCause()).isNull();
            assertThat(ex.getMessage()).doesNotContain("BODY_MARKER", "TICKET_MARKER");
        });
    }
}
