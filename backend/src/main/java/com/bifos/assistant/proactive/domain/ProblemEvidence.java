package com.bifos.assistant.proactive.domain;

import java.time.Instant;

/**
 * 문제 후보의 근거가 된 발견 하나의 참조다. 원문 본문은 두지 않는다.
 *
 * @param sourceUrl 원문 주소 검사를 통과한 주소다
 * @param checkedAt 원문을 확인한 시각이다
 */
public record ProblemEvidence(String topicKey, String sourceUrl, Instant checkedAt) {}
