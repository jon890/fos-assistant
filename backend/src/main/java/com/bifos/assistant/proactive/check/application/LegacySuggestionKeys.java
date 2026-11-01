package com.bifos.assistant.proactive.check.application;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 옛 발견과 문제 후보를 옮긴 제안 줄의 중복 열쇠다(ADR-20261101 / proactive-suggestion). 형식은 {@code legacy:<옛 테이블>:<옛 번호>} 이고
 * {@code follow_up.dedupe_key} 에 둔다. 먼저 살펴보기가 새로 낸 제안의 열쇠는 SHA-256 16진수라 겹치지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class LegacySuggestionKeys {

    private static final String PREFIX = "legacy:";


    static String finding(Long findingId) {
        return PREFIX + "proactive_check_finding:" + findingId;
    }


    static String problem(Long problemId) {
        return PREFIX + "proactive_check_problem:" + problemId;
    }
}
