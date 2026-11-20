package com.bifos.assistant.connector.oauth.application.dto;

import com.bifos.assistant.hermes.application.dto.ConnectorOAuth.Credentials;

/**
 * 값이 모두 채워진 OAuth 앱이다. 대시보드에 보낼 자격을 담으므로 응답과 로그에 쓰지 않는다.
 *
 * @param secretName 서버 비밀값 줄의 이름. 관리자 알림의 원인을 가리킨다
 * @param appSlug 플러그인마다 두는 앱의 이름. 공유 앱이면 null
 */
public record ConnectorOAuthApp(String secretName, Credentials credentials, String appSlug) {

    @Override
    public String toString() {
        return "ConnectorOAuthApp[" + secretName + "]";
    }
}
