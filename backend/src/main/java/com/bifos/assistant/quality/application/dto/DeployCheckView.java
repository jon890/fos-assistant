package com.bifos.assistant.quality.application.dto;

import com.bifos.assistant.quality.domain.FeatureResult;
import com.bifos.assistant.quality.domain.type.CheckReason;
import com.bifos.assistant.quality.domain.type.FeatureStatus;
import com.bifos.assistant.quality.domain.QualityRun;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 배포 뒤 확인 한 번이다. 관리자 API 와 품질 화면이 읽는다.
 *
 * @param trigger {@code PIPELINE} 이나 {@code ADMIN}
 * @param status {@code RUNNING}, {@code PASSED}, {@code FAILED}. 파이프라인이 이 값을 읽는다
 * @param finishedAt 도는 동안 null
 * @param elapsedMs 도는 동안 null
 * @param results 끝난 기능의 결과. 도는 동안에는 끝난 것만 있다
 * @param environmentError 통과하지 못한 기능이 있고 모두 평가 비서의 Control Plane 연결이 없던 환경 오류(까닭 {@code ENVIRONMENT})다. 규칙 미달과 섞이면
 * 거짓이고, 화면은 결과의 까닭으로 「일부 환경 오류」 를 따로 보인다
 */
public record DeployCheckView(
    UUID publicId,
    String trigger,
    String status,
    Instant startedAt,
    Instant finishedAt,
    Long elapsedMs,
    int itemCount,
    int modelCallCount,
    List<FeatureResultView> results,
    boolean environmentError) {

    public DeployCheckView {
        results = List.copyOf(results);
    }


    public static DeployCheckView of(QualityRun run) {
        return new DeployCheckView(
            run.publicId(),
            run.trigger().name(),
            run.status().name(),
            run.startedAt(),
            run.finishedAt(),
            run.elapsedMs(),
            run.itemCount(),
            run.modelCallCount(),
            run.results().stream().map(FeatureResultView::of).toList(),
            onlyEnvironmentErrors(run.results()));
    }


    private static boolean onlyEnvironmentErrors(List<FeatureResult> results) {
        List<FeatureResult> failed = results.stream().filter(result -> result.status() != FeatureStatus.PASSED).toList();
        return !failed.isEmpty() && failed.stream().allMatch(result -> CheckReason.ENVIRONMENT.name().equals(result.reasonCode()));
    }


    /**
     * 기능 하나의 결과다.
     *
     * @param reasonCode 통과하지 못한 까닭의 고정 코드. 통과면 null
     * @param conversationPublicId 실패한 기능의 평가 대화. 통과했거나 보관 기간이 지나면 null
     * @param executionIds 실패한 기능이 돈 루트 실행 번호들. 통과했거나 보관 기간이 지나면 빈 목록
     */
    public record FeatureResultView(
        String feature,
        String status,
        String reasonCode,
        long elapsedMs,
        UUID conversationPublicId,
        List<Long> executionIds) {

        static FeatureResultView of(FeatureResult result) {
            return new FeatureResultView(
                result.feature().name(),
                result.status().name(),
                result.reasonCode(),
                result.elapsedMs(),
                result.conversationPublicId(),
                result.executionIds());
        }
    }
}
