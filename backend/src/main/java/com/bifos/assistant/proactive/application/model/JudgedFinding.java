package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.proactive.domain.type.FindingKind;
import com.bifos.assistant.proactive.domain.type.FindingReason;
import java.time.Instant;

/**
 * 검사를 마친 발견이다.
 *
 * @param reason 「참고」 로 내린 까닭이다. {@link FindingKind#NEW} 면 비어 있다
 * @param sourceUrl 원문 주소 검사를 통과했을 때만 채운다
 * @param checkedAt 확인 시각을 읽었을 때만 채운다
 */
public record JudgedFinding(
        CheckResultBlock.Finding finding, FindingKind kind, FindingReason reason, String sourceUrl, Instant checkedAt) {}
