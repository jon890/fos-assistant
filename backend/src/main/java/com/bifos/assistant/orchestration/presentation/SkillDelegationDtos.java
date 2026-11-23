package com.bifos.assistant.orchestration.presentation;

import com.bifos.assistant.orchestration.application.dto.SkillDelegationStarted;
import com.bifos.assistant.orchestration.application.dto.SkillDelegationTarget;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 스킬 맡기기 경로의 요청과 응답이다(docs/features/assistant-hub/spec.md 의 REQ10). */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SkillDelegationDtos {

    /**
     * 고른 스킬을 맡긴다.
     *
     * @param conversationId 이어 쓸 대화의 공개 식별자. 없으면 기본 비서와 새 대화를 만든다
     * @param agentCode 맡을 에이전트
     * @param text {@code /<에이전트 이름> · <스킬> [나머지]}
     */
    public record SkillDelegationRequest(
        UUID conversationId,
        @NotBlank @Size(max = 64) String agentCode,
        @NotBlank @Size(max = 8000) String text) {
    }

    /** 맡긴 대화와 실행이다. 결과는 대화의 깨우기가 따로 전한다. */
    public record SkillDelegationResponse(UUID conversationId, Long executionId) {

        public static SkillDelegationResponse from(SkillDelegationStarted started) {
            return new SkillDelegationResponse(started.conversation().publicId(), started.executionId());
        }
    }

    /** {@code /} 목록에 보일 에이전트 하나와 그 스킬이다. 스킬마다 목록 줄에 한 줄로 보일 설명이 있다. */
    public record SkillDelegationTargetResponse(String agentCode, String agentName, List<SkillResponse> skills) {

        public static SkillDelegationTargetResponse from(SkillDelegationTarget target) {
            return new SkillDelegationTargetResponse(target.agentCode(), target.agentName(),
                target.skills().stream().map(skill -> new SkillResponse(skill.name(), skill.description())).toList());
        }
    }

    /** 맡길 스킬 하나의 이름과 설명이다. */
    public record SkillResponse(String name, String description) {
    }
}
