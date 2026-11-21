package com.bifos.assistant.connector.connection.application.dto;

/**
 * 단추 연결을 해제할 때 서비스 쪽 허락을 취소한 결과다(스펙 REQ8). 취소가 실패해도 해제는 끝난다.
 *
 * @param settingsUrl 사용자가 허락을 직접 거둘 수 있는 서비스의 설정 화면. {@code FAILED} 일 때만 있고, 선언을 알 수 없으면 null
 * @param installSettingsUrl 앱 설치를 지울 수 있는 화면. 설치가 따로 남는 서비스(GitHub App)만 있고 취소가 성공해도 담는다
 */
public record ConnectionOAuthRevoke(Status status, String settingsUrl, String installSettingsUrl) {

    /** 취소의 결과 종류다. 소문자로 응답에 나간다. */
    public enum Status {
        /** 서비스가 허락을 취소했다. */
        REVOKED,
        /** 취소하지 못했거나 다른 연결이 같은 허락을 써서 건너뛰었다. 사용자가 설정 화면에서 직접 거둔다. */
        FAILED
    }
}
