package com.bifos.assistant.quality.application.dto;

import java.util.List;

/**
 * 평가 전용 사용자가 준비됐는지다. 빠진 것이 없으면 준비됐다.
 *
 * @param hermesProfile 평가 사용자의 기본 profile. 사용자가 없으면 null
 * @param missing 빠진 것. 값은 {@link Missing} 의 이름이다
 */
public record EvaluationUserStatus(String hermesProfile, List<String> missing) {

    public EvaluationUserStatus {
        missing = List.copyOf(missing);
    }


    public boolean ready() {
        return missing.isEmpty();
    }


    /** 준비에서 빠질 수 있는 것이다. */
    public enum Missing {
        /** 평가 사용자가 없다. */
        USER,
        /** 기본 에이전트가 없다. */
        PRIMARY_AGENT,
        /** 위임 대상 에이전트가 없다. */
        DELEGATE_AGENT,
        /** 미리 넣는 기억이 없다. */
        MEMORY,
        /** 그림·사진 도구가 실행 공간 안에서 켜지지 않았다. 운영이 평가 profile 을 실행 공간과 첨부 정책에 등록해야 한다. */
        POLICY_REGISTRATION_REQUIRED
    }
}
