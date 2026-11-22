package com.bifos.assistant.admin.quality.presentation;

import com.bifos.assistant.quality.application.dto.EvaluationUserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import com.bifos.assistant.model.domain.type.ModelTier;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 품질 관리자 API 의 요청과 응답이다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class QualityAdminDtos {

    /**
     * 평가 전용 사용자를 준비하는 요청이다. profile 이름 규칙은 사람 더하기와 같다.
     *
     * @param hermesProfile 운영이 실행 공간과 첨부 정책에 등록한 profile 이름. 이미 준비된 사용자가 있으면 무시한다
     */
    public record PrepareEvaluationUserRequest(@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String hermesProfile) {
    }

    /**
     * 평가 전용 사용자의 준비 상태다.
     *
     * @param missing 빠진 것. 비었으면 준비됐다
     */
    public record EvaluationUserView(boolean ready, String hermesProfile, List<String> missing) {

        static EvaluationUserView of(EvaluationUserStatus status) {
            return new EvaluationUserView(status.ready(), status.hermesProfile(), status.missing());
        }
    }

    /**
     * 평가를 시작하는 요청이다.
     *
     * @param bundles 고를 묶음 id. 비거나 없으면 전체
     * @param tier 모델 단계. 없으면 평가 사용자에게 걸리는 기본 단계다. 모르는 값이면 400
     */
    public record StartEvalRequest(List<String> bundles, ModelTier tier) {
    }
}
