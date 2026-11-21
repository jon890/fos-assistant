package com.bifos.assistant.quality.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bifos.assistant.quality.domain.type.EvalItemStatus;
import com.bifos.assistant.quality.domain.type.FeatureStatus;
import com.bifos.assistant.quality.domain.type.QualityFeature;
import com.bifos.assistant.quality.domain.type.QualityRunKind;
import com.bifos.assistant.quality.domain.type.QualityRunStatus;
import com.bifos.assistant.quality.domain.type.QualityRunTrigger;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QualityRunTest {

    private static final Instant START = Instant.parse("2026-11-20T01:00:00Z");


    private static QualityRun running() {
        return QualityRun.start(1L, QualityRunKind.DEPLOY_CHECK, QualityRunTrigger.PIPELINE, 7L, START);
    }


    private static FeatureResult failed(QualityFeature feature) {
        return new FeatureResult(feature, FeatureStatus.FAILED, "NO_SOURCES", 1200, UUID.randomUUID(), List.of(11L, 12L));
    }


    @Test
    @DisplayName("기능 결과를 더하면 문항 수와 모델 호출 수가 쌓인다")
    void recordsFeatureResults() {
        QualityRun run = running();

        run.record(failed(QualityFeature.SEARCH), 2);
        run.record(new FeatureResult(QualityFeature.IMAGE, FeatureStatus.PASSED, null, 900, null, List.of()), 1);

        assertThat(run.itemCount()).isEqualTo(2);
        assertThat(run.modelCallCount()).isEqualTo(3);
        assertThat(run.results()).extracting(FeatureResult::feature)
            .containsExactly(QualityFeature.SEARCH, QualityFeature.IMAGE);
        assertThat(run.running()).isTrue();
    }


    @Test
    @DisplayName("끝내면 상태와 끝난 시각, 걸린 시간이 적힌다")
    void finishWritesOutcome() {
        QualityRun run = running();

        run.finish(QualityRunStatus.FAILED, START.plusSeconds(90));

        assertThat(run.status()).isEqualTo(QualityRunStatus.FAILED);
        assertThat(run.finishedAt()).isEqualTo(START.plusSeconds(90));
        assertThat(run.elapsedMs()).isEqualTo(90_000L);
    }


    @Test
    @DisplayName("끝난 줄은 다시 바뀌지 않는다")
    void finishedRunDoesNotChange() {
        QualityRun run = running();
        run.finish(QualityRunStatus.PASSED, START.plusSeconds(5));

        assertThatThrownBy(() -> run.finish(QualityRunStatus.FAILED, START.plusSeconds(6)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.record(failed(QualityFeature.SEARCH), 1)).isInstanceOf(IllegalStateException.class);
        assertThat(run.status()).isEqualTo(QualityRunStatus.PASSED);
    }


    @Test
    @DisplayName("도는 상태로는 끝내지 않는다")
    void finishRejectsRunning() {
        assertThatThrownBy(() -> running().finish(QualityRunStatus.RUNNING, START))
            .isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    @DisplayName("30일 정리는 대화와 실행 링크만 비우고 결과와 까닭은 남긴다")
    void dropLinksKeepsVerdicts() {
        QualityRun run = running();
        run.record(failed(QualityFeature.SEARCH), 1);
        run.finish(QualityRunStatus.FAILED, START.plusSeconds(1));

        run.dropLinks();

        FeatureResult kept = run.results().getFirst();
        assertThat(kept.conversationPublicId()).isNull();
        assertThat(kept.executionIds()).isEmpty();
        assertThat(kept.status()).isEqualTo(FeatureStatus.FAILED);
        assertThat(kept.reasonCode()).isEqualTo("NO_SOURCES");
    }


    @Test
    @DisplayName("지우지 못한 대화의 링크는 남기고 지운 대화의 링크만 비운다")
    void dropLinksKeepsUnremovedConversations() {
        QualityRun run = running();
        FeatureResult removed = new FeatureResult(QualityFeature.SEARCH, FeatureStatus.FAILED, "NO_SOURCES", 1, UUID.randomUUID(), List.of(1L));
        FeatureResult kept = new FeatureResult(QualityFeature.IMAGE, FeatureStatus.FAILED, "NO_IMAGE", 1, UUID.randomUUID(), List.of(2L));
        run.record(removed, 1);
        run.record(kept, 1);
        run.finish(QualityRunStatus.FAILED, START.plusSeconds(1));

        run.dropLinks(Set.of(removed.conversationPublicId()));

        assertThat(run.results().get(0).conversationPublicId()).isNull();
        assertThat(run.results().get(0).executionIds()).isEmpty();
        assertThat(run.results().get(1).conversationPublicId()).isEqualTo(kept.conversationPublicId());
        assertThat(run.results().get(1).executionIds()).containsExactly(2L);
    }


    @Test
    @DisplayName("평가 줄은 고른 문항 수를 그대로 두고 결과를 바꿔 적으며, 배포 뒤 확인 메서드는 거절한다")
    void evalRunKeepsItemCountAndRejectsDeployCheckMethods() {
        QualityRun run = QualityRun.startEval(1L, QualityRunTrigger.ADMIN, 7L, 5, EvalResult.started("MEDIUM", List.of("service-concepts")), START);
        EvalResult.ItemResult item = new EvalResult.ItemResult("service-concepts", "files-put", EvalItemStatus.PASSED, null, List.of(), 1200,
            START, 31L, null, List.of(), false);

        run.recordEval(run.evalResult().withItem(item), 2);

        assertThat(run.kind()).isEqualTo(QualityRunKind.EVAL);
        assertThat(run.itemCount()).isEqualTo(5);
        assertThat(run.modelCallCount()).isEqualTo(2);
        assertThat(run.evalResult().tier()).isEqualTo("MEDIUM");
        assertThat(run.evalResult().items()).containsExactly(item);
        assertThatThrownBy(run::results).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.record(failed(QualityFeature.SEARCH), 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> running().evalResult()).isInstanceOf(IllegalStateException.class);
    }


    @Test
    @DisplayName("같은 문항을 다시 적으면 앞 줄을 바꾸고, 끝난 평가도 비우기 끝남을 적을 수 있다")
    void evalItemIsReplacedAndCleanedAfterFinish() {
        QualityRun run = QualityRun.startEval(1L, QualityRunTrigger.ADMIN, 7L, 1, EvalResult.started("MEDIUM", List.of("b")), START);
        EvalResult.ItemResult started = new EvalResult.ItemResult("b", "q", EvalItemStatus.RUNNING, null, List.of(), 0, START, 31L, null, List.of(), false);
        run.recordEval(run.evalResult().withItem(started), 0);
        run.recordEval(run.evalResult().withItem(new EvalResult.ItemResult("b", "q", EvalItemStatus.FAILED, "MISSING_TOOL", List.of("MISSING_TOOL:task_create"),
            10, START, 31L, UUID.randomUUID(), List.of(5L), false)), 1);
        run.finish(QualityRunStatus.FAILED, START.plusSeconds(5));

        run.replaceEval(run.evalResult().markCleaned());

        assertThat(run.evalResult().items()).singleElement().extracting(EvalResult.ItemResult::status).isEqualTo(EvalItemStatus.FAILED);
        assertThat(run.evalResult().cleaned()).isTrue();
        assertThatThrownBy(() -> run.recordEval(run.evalResult(), 0)).isInstanceOf(IllegalStateException.class);
    }
}
