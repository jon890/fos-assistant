package com.bifos.assistant.attention.application.model;

import com.bifos.assistant.attention.domain.type.AttentionTrigger;
import com.bifos.assistant.attention.domain.type.CardKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 원래 기록에서 읽은 판정 후보 하나다. 판정 전이라 억제와 중복을 아직 보지 않았다.
 *
 * @param card 이 후보가 들 카드
 * @param itemKey 항목 열쇠. 같은 열쇠가 앞선 카드에 이미 있으면 중복이다
 * @param stateKey 항목의 지금 상태의 지문. 숨기기는 이 값이 바뀔 때까지 걸린다
 * @param resolved 원래 기록이 해결된 상태인가
 * @param nowSignal {@code NOW} 조건을 채웠는가
 * @param title 사람에게 보일 이름. 평문이다
 * @param conversationId 이어지는 대화의 공개 식별자. 없으면 null
 * @param agentName 에이전트 이름. 없으면 null
 * @param at 항목의 정렬 시각
 * @param execution 맡긴 일 항목의 실행. 해당하지 않으면 null
 * @param actionId 승인 대기 항목의 승인 줄 공개 식별자. 해당하지 않으면 null
 * @param followUp 할 일 항목의 값. 해당하지 않으면 null
 */
public record AttentionCandidate(
        CardKey card,
        String itemKey,
        String stateKey,
        AttentionTrigger trigger,
        boolean resolved,
        boolean nowSignal,
        List<AttentionSignal> signals,
        AttentionConfidence confidence,
        String title,
        UUID conversationId,
        String agentName,
        Instant at,
        List<AttentionSourceRef> sources,
        AttentionExecutionRef execution,
        UUID actionId,
        AttentionFollowUpRef followUp) {}
