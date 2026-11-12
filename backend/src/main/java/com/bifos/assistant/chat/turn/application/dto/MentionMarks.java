package com.bifos.assistant.chat.turn.application.dto;

import com.bifos.assistant.agent.application.dto.AgentLabel;
import java.util.Map;

/**
 * 대화 이력의 줄에 붙일 {@code @} 맡김 값이다(docs/features/agent-team/design.md 의 「PR4 @ 멘션」).
 *
 * @param questions 맡김 질문 줄의 메시지 번호마다 그 맡김. 모양은 맡긴 줄과 같고 부모 실행 번호가 비어 있다
 * @param answers 맡김 답 줄의 실행 번호마다 맡은 에이전트의 이름표
 */
public record MentionMarks(Map<Long, DelegationLine> questions, Map<Long, AgentLabel> answers) {

    public static final MentionMarks NONE = new MentionMarks(Map.of(), Map.of());


    public MentionMarks {
        questions = Map.copyOf(questions);
        answers = Map.copyOf(answers);
    }
}
