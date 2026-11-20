package com.bifos.assistant.connector.connection.application.port;

import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;

/**
 * 연결의 등록, 확인, 해제가 단추 연결(OAuth)에 맡기는 일이다(ADR-20261120 / connector-oauth).
 *
 * <p>구현은 위 하위 기능 {@code connector.oauth} 가 갖는다. 연결 쪽은 토큰과 앱 자격을 모른다.
 */
public interface ConnectionOAuth {

    /** 그 플러그인의 단추를 지금 쓸 수 있는가. 앱 값이 모두 있고 토큰 통로 주소를 만들 수 있어야 한다. */
    boolean ready(ConnectorManifest manifest);


    /** 값을 붙여 넣어 다시 등록했거나 해제했다. 메모리의 토큰을 지우고 끊김 알림을 읽음으로 바꾼다. 연결의 트랜잭션 안에서 부른다. */
    void forget(ConnectorConnection connection);
}
