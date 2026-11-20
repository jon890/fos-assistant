package com.bifos.assistant.quality.domain;

import com.bifos.assistant.quality.domain.type.FeatureStatus;
import com.bifos.assistant.quality.domain.type.QualityFeature;
import java.util.List;
import java.util.UUID;

/**
 * {@code quality_run.result_json} 의 기능 하나다. 질문과 답 본문은 담지 않는다.
 *
 * @param reasonCode 통과하지 못한 까닭의 고정 코드. 통과면 null
 * @param conversationPublicId 실패한 기능의 평가 대화. 통과했거나 30일이 지나면 null
 * @param executionIds 그 기능이 돈 루트 실행 번호들. 통과했거나 30일이 지나면 빈 목록
 */
public record FeatureResult(
    QualityFeature feature,
    FeatureStatus status,
    String reasonCode,
    long elapsedMs,
    UUID conversationPublicId,
    List<Long> executionIds) {

    public FeatureResult {
        executionIds = executionIds == null ? List.of() : List.copyOf(executionIds);
    }


    /** 30일이 지나 대화를 지운 뒤 남길 모양이다. 결과와 까닭, 걸린 시간만 남는다. */
    public FeatureResult withoutLinks() {
        return new FeatureResult(feature, status, reasonCode, elapsedMs, null, List.of());
    }
}
