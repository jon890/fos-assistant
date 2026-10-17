package com.bifos.assistant.connector.contentdraft.application;

import com.bifos.assistant.chat.domain.type.ContentDraftJobStatus;
import com.bifos.assistant.connector.contentdraft.application.dto.ContentDraftWorkerStatus;
import com.bifos.assistant.connector.contentdraft.application.dto.ContentDraftSaveTicketPayload;
import com.bifos.assistant.hermes.application.dto.ContentDraftWorkerStamp;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 외부 값을 강제 변환하지 않고 정확한 stamp·타입·허용 목록으로 읽는다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ContentDraftWorkerStatusDecoder {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CODE = "[a-z][a-z_]{0,63}";
    private static final Set<String> STAGES = Set.of("queued", "open", "fill", "photos", "components", "settings", "save", "state");


    public static ContentDraftWorkerStamp stamp(String profile, ContentDraftSaveTicketPayload job) {
        return ContentDraftWorkerStamp.of(profile, job.snapshotId(), job.jobId(), job.snapshotHash(), job.bindingId(), job.connectionId(),
            job.bindingUpdatedAt(), job.connectionUpdatedAt());
    }


    public static ContentDraftWorkerStatus decode(JsonNode node, ContentDraftWorkerStamp stamp, boolean start) {
        try {
            var keys = start ? Set.of("job_id", "status", "stamp", "error_code")
                : Set.of("job_id", "status", "stamp", "stage", "save_clicked", "finished_at", "saved_before", "saved_after", "error_code");
            // error_reason 은 worker 가 사유가 있을 때만 더한다. 옛 worker 는 칸이 없어 start 가 아닌 응답에서만 선택으로 받는다.
            var reasonPresent = !start && node.isObject() && node.has("error_reason");
            if (reasonPresent) {
                keys = new HashSet<>(keys);
                keys.add("error_reason");
            }
            require(node.isObject() && node.propertyNames().equals(keys) && node.path("stamp").equals(JSON.valueToTree(stamp))
                && node.path("job_id").isString() && node.path("job_id").stringValue().equals(stamp.jobId().toString())
                && node.path("status").isString());
            var status = switch (node.path("status").stringValue()) {
                case "running" -> ContentDraftJobStatus.SAVING;
                case "succeeded" -> ContentDraftJobStatus.SUCCEEDED;
                case "failed" -> ContentDraftJobStatus.FAILED;
                case "unknown" -> ContentDraftJobStatus.UNKNOWN;
                default -> throw unavailable();
            };
            var error = node.path("error_code");
            require(error.isNull() || error.isString() && error.stringValue().matches(CODE));
            var reason = node.path("error_reason");
            require(!reasonPresent || reason.isString() && reason.stringValue().matches(CODE));
            String stage = null;
            boolean clicked = false;
            if (!start) {
                var rawStage = node.path("stage");
                require(rawStage.isNull() || rawStage.isString() && STAGES.contains(rawStage.stringValue()));
                stage = rawStage.isNull() ? null : rawStage.stringValue();
                require(node.path("save_clicked").isBoolean());
                clicked = node.path("save_clicked").booleanValue();
                var finished = node.path("finished_at");
                require(finished.isNull() || finished.isString() && finished.stringValue().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
                if (!finished.isNull()) {
                    Instant.parse(finished.stringValue());
                }
                for (String name : Set.of("saved_before", "saved_after")) {
                    var value = node.path(name);
                    require(value.isNull() || status == ContentDraftJobStatus.SUCCEEDED && value.isIntegralNumber()
                        && value.canConvertToLong() && value.longValue() >= 0 && value.longValue() <= 9007199254740991L);
                }
            }
            return new ContentDraftWorkerStatus(status, stage, clicked, error.isNull() ? null : error.stringValue(),
                reasonPresent ? reason.stringValue() : null);
        } catch (RuntimeException ignored) {
            throw unavailable();
        }
    }


    public static ApiException unavailable() {
        return new ApiException(ErrorCode.CONTENT_DRAFT_JOB_UNAVAILABLE, "원고 저장 상태를 확인할 수 없습니다.");
    }


    private static void require(boolean valid) {
        if (!valid) {
            throw unavailable();
        }
    }
}
