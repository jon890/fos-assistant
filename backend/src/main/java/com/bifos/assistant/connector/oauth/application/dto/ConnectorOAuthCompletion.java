package com.bifos.assistant.connector.oauth.application.dto;

/**
 * 서비스 화면에서 돌아온 요청의 결과다. 실패도 오류가 아니라 이 값으로 답해 연결 화면이 까닭과 「다시 시도」 를 한 모양으로 그린다
 * (스펙 DECISION20).
 *
 * @param connectorId 돌아갈 연결 화면의 플러그인. 상태 값을 믿을 수 없으면 null
 * @param authorizeUrl {@code REAUTHORIZE} 일 때 한 번 더 보낼 로그인 주소
 * @param reason {@code VERIFY_FAILED} 일 때 확인 도구가 낸 오류 코드
 */
public record ConnectorOAuthCompletion(Outcome outcome, String connectorId, String authorizeUrl, String reason) {

    /** 돌아오기의 결과 종류다. 이름 그대로 응답에 나간다. */
    public enum Outcome {
        /** 연결을 만들었다. */
        CONNECTED,
        /** 이미 설치된 앱이라 설치 화면이 코드 없이 끝났다. 허락 화면으로 한 번 더 보낸다. */
        REAUTHORIZE,
        /** 사용자가 거절했거나 받은 코드를 서비스가 받지 않았다. */
        DENIED,
        /** 상태 값이 위조됐거나 만료됐거나 이미 썼거나 다른 사용자가 시작한 것이다. */
        INVALID_STATE,
        /** 요청한 권한 가운데 일부만 허락했다. */
        SCOPE_DENIED,
        /** 관리자의 앱 설정이 틀려 토큰을 받지 못했다. */
        APP_REJECTED,
        /** 확인 도구가 실패했다. */
        VERIFY_FAILED,
        /** 같은 사용자가 나중에 시작한 흐름이 이겼다. */
        SUPERSEDED,
        /** 대시보드나 서비스가 잠시 답하지 않았다. */
        UNAVAILABLE
    }


    public static ConnectorOAuthCompletion of(Outcome outcome, String connectorId) {
        return new ConnectorOAuthCompletion(outcome, connectorId, null, null);
    }
}
