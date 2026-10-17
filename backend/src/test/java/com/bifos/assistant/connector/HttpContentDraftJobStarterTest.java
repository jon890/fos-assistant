package com.bifos.assistant.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bifos.assistant.connector.application.ContentDraftWorkerStatusDecoder;
import com.bifos.assistant.connector.application.HttpContentDraftJobStarter;
import com.bifos.assistant.connector.application.model.ContentDraftSaveTicketPayload;
import com.bifos.assistant.hermes.infra.HttpContentDraftSnapshotClient;
import com.bifos.assistant.hermes.application.dto.ContentDraftWorkerStamp;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 실제 시작 전송 구현이 worker 의 시작 결과를 접수, 시작 전 거절, 결과 모름으로 옮기는지 가짜 client 로 확인한다. */
class HttpContentDraftJobStarterTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpContentDraftSnapshotClient client = mock(HttpContentDraftSnapshotClient.class);
    private final HttpContentDraftJobStarter starter = new HttpContentDraftJobStarter(client);
    private final ContentDraftSaveTicketPayload job = new ContentDraftSaveTicketPayload(UUID.randomUUID(), UUID.randomUUID(),
        "a".repeat(64), UUID.randomUUID(), 1L, 2L, 3L, 4L, Instant.parse("2026-10-11T00:00:00Z"),
        Instant.parse("2026-10-11T00:00:00.123400Z"), null, null);
    private final ContentDraftWorkerStamp stamp = ContentDraftWorkerStatusDecoder.stamp("fixture", job);
    private final JsonNode snapshot = json.createObjectNode().put("snapshotHash", "a".repeat(64));


    @Test
    @DisplayName("running과 succeeded는 접수이고 예약 stamp와 ticket을 그대로 넘긴다")
    void acceptsRunningAndSucceeded() {
        for (var status : new String[] {"running", "succeeded"}) {
            answer(result(status, null));
            assertThat(start()).isEmpty();
        }
        verify(client, times(2)).start(eq("fixture"), eq("snapshot-test"), eq(snapshot), eq(stamp), eq("TICKET"));
    }


    @Test
    @DisplayName("failed는 시작 전 거절이고 worker code를 쓰며 code가 없으면 start_failed다")
    void rejectsFailedWithWorkerCode() {
        answer(result("failed", "naver_blog_busy"));
        assertThat(start()).contains("naver_blog_busy");
        answer(result("failed", null));
        assertThat(start()).contains("start_failed");
    }


    @Test
    @DisplayName("unknown과 다른 stamp, 오류 code 결과는 결과 모름 예외다")
    void throwsWhenResultIsUnknownOrMismatched() {
        answer(result("unknown", null));
        unknown();
        var foreign = result("running", null);
        ((ObjectNode)foreign.path("stamp")).put("bindingId", "99");
        answer(foreign);
        unknown();
        answer(json.createObjectNode().put("error", "start_unknown"));
        unknown();
        when(client.start(any(), any(), any(), any(), any())).thenThrow(new ApiException(ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE, "x"));
        unknown();
    }


    private Optional<String> start() {
        return starter.start("fixture", "snapshot-test", snapshot, job, "TICKET");
    }


    private void unknown() {
        assertThatThrownBy(this::start).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo(ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE));
    }


    private void answer(JsonNode result) {
        when(client.start(any(), any(), any(), any(), any())).thenReturn(result);
    }


    private ObjectNode result(String status, String errorCode) {
        return json.createObjectNode().put("job_id", stamp.jobId().toString()).<ObjectNode>set("stamp", json.valueToTree(stamp))
            .put("status", status).put("error_code", errorCode);
    }
}
