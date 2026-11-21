package com.bifos.assistant.quality.domain;

import com.bifos.assistant.quality.domain.type.QualityRunKind;
import com.bifos.assistant.quality.domain.type.QualityRunStatus;
import com.bifos.assistant.quality.domain.type.QualityRunTrigger;
import com.bifos.assistant.shared.domain.CreatedAtEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * 배포 뒤 확인이나 품질 평가 한 번이다. 칸의 뜻은 {@code backend/docs/data-schema.md} 의 「품질 확인 표」 가 갖는다.
 *
 * <p>질문과 답 본문은 담지 않는다. 기능 결과는 기능이 끝날 때마다 더해, 도중에 서버가 다시 떠도 실패한 기능의 대화 번호가 남는다.
 */
@Entity
@Table(name = "quality_run")
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QualityRun extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 화면과 API 가 쓰는 번호다. 넣을 때 Hibernate 가 v7 을 채운다. */
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "public_id", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID publicId;

    @Column(name = "group_id", nullable = false, updatable = false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 32)
    private QualityRunKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, updatable = false, length = 32)
    private QualityRunTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private QualityRunStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "model_call_count", nullable = false)
    private int modelCallCount;

    @Column(name = "elapsed_ms")
    private Long elapsedMs;

    @Convert(converter = FeatureResultsConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "JSON")
    private List<FeatureResult> results = new ArrayList<>();

    @Column(name = "requested_by_user_id", updatable = false)
    private Long requestedByUserId;


    /** 도는 확인 한 줄을 만든다. */
    public static QualityRun start(
        Long groupId, QualityRunKind kind, QualityRunTrigger trigger, Long requestedByUserId, Instant now) {
        QualityRun run = new QualityRun();
        run.groupId = Objects.requireNonNull(groupId, "groupId");
        run.kind = Objects.requireNonNull(kind, "kind");
        run.trigger = Objects.requireNonNull(trigger, "trigger");
        run.status = QualityRunStatus.RUNNING;
        run.startedAt = now.truncatedTo(ChronoUnit.MICROS);
        run.requestedByUserId = requestedByUserId;
        return run;
    }


    /** 기능 하나의 결과를 더한다. 끝난 줄에는 더하지 않는다. */
    public void record(FeatureResult result, int modelCalls) {
        requireRunning();
        List<FeatureResult> next = new ArrayList<>(results());
        next.add(Objects.requireNonNull(result, "result"));
        results = next;
        itemCount = next.size();
        modelCallCount += Math.max(0, modelCalls);
    }


    /** 확인을 끝낸다. 도는 줄에서만 바꾸고, 끝난 줄은 다시 바뀌지 않는다. */
    public void finish(QualityRunStatus outcome, Instant now) {
        requireRunning();
        if (outcome == QualityRunStatus.RUNNING) {
            throw new IllegalArgumentException("outcome must be a finished status");
        }
        status = outcome;
        finishedAt = now.truncatedTo(ChronoUnit.MICROS);
        elapsedMs = Duration.between(startedAt, finishedAt).toMillis();
    }


    /** 30일이 지난 실패 기능의 대화와 실행 링크를 비운다. 결과와 숫자는 남는다. */
    public void dropLinks() {
        results = results().stream().map(FeatureResult::withoutLinks).toList();
    }


    /**
     * 지운 대화의 링크만 비운다. 지우지 못한 대화의 링크는 남겨 다음 정리가 다시 찾게 한다. 대화 없이 실행 번호만 남은 기능은 비운다.
     *
     * @param removed 지웠거나 이미 없는 대화의 공개 식별자
     */
    public void dropLinks(Set<UUID> removed) {
        results = results().stream()
            .map(result -> result.conversationPublicId() == null || removed.contains(result.conversationPublicId())
                ? result.withoutLinks()
                : result)
            .toList();
    }


    public List<FeatureResult> results() {
        return results == null ? List.of() : List.copyOf(results);
    }


    public boolean running() {
        return status == QualityRunStatus.RUNNING;
    }


    private void requireRunning() {
        if (!running()) {
            throw new IllegalStateException("quality run already finished");
        }
    }
}
