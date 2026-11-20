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
        /**
         * 기본 에이전트가 없다. 기본 비서는 지울 수 없어(PRIMARY_AGENT_PROTECTED) 첫 로그인이 허용 목록 행을 찾지 못했을 때만 생긴다.
         * 다시 준비해도 채우지 않는다. 사람이 허용 목록 행과 평가 사용자를 확인해 조치한다.
         */
        PRIMARY_AGENT,
        /** 위임 대상 에이전트가 없다. */
        DELEGATE_AGENT,
        /** 미리 넣는 기억이 없다. */
        MEMORY,
        /**
         * 기본 비서의 도구(첫 로그인 기본 도구와 그림·사진 도구)가 다 켜지지 않았다. 대개 운영이 평가 profile 을 실행 공간과 첨부
         * 정책에 아직 등록하지 않은 것이다. 처음 준비한 직후에는 도구 켜기가 요청 밖에서 도는 중일 수 있어 잠시 뒤 다시 읽는다. 등록한
         * 뒤에도 남으면 다시 준비한다.
         */
        POLICY_REGISTRATION_REQUIRED,
        /** Hermes 가 기본 비서의 도구 목록을 돌려주지 않았다. 도구가 켜졌는지 모른다. */
        TOOLS_UNREADABLE
    }
}
