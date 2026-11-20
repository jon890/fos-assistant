package com.bifos.assistant.connector.connection.application.port;

import com.bifos.assistant.connector.connection.application.dto.ConnectionOAuthRevoke;
import com.bifos.assistant.connector.connection.domain.ConnectorConnection;
import com.bifos.assistant.hermes.application.dto.ConnectorCallError;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import java.util.Optional;

/**
 * 연결의 등록, 확인, 해제가 단추 연결(OAuth)에 맡기는 일이다(ADR-20261120 / connector-oauth).
 *
 * <p>구현은 위 하위 기능 {@code connector.oauth} 가 갖는다. 연결 쪽은 토큰과 앱 자격을 모른다.
 */
public interface ConnectionOAuth {

    /** 그 플러그인의 단추를 지금 쓸 수 있는가. 앱 값이 모두 있고 토큰 통로 주소를 만들 수 있어야 한다. */
    boolean ready(ConnectorManifest manifest);


    /**
     * 「연결 다시 확인」 이 확인 도구를 부르기 전에 토큰을 한 번 새로 받는다(스펙 DECISION19). 다시 연결 필요였던 연결이 새 토큰을 받으면
     * 그 표시를 지워 확인 도구가 토큰 통로를 쓸 수 있게 한다. 트랜잭션 밖에서 부른다.
     *
     * @return 확인 도구를 부르지 않고 끝낼 까닭. 서비스가 갱신을 거절했으면 {@code CREDENTIAL_REJECTED}, 서비스나 앱 설정 문제로 받지
     * 못했으면 {@code UNAVAILABLE}. 확인 도구를 불러도 되면 비어 있다
     */
    Optional<ConnectorCallError> refreshForCheck(long connectionId);


    /**
     * 해제하는 단추 연결의 서비스 쪽 허락을 취소한다(스펙 REQ8). 바인딩을 모두 뗀 뒤 보관 파일을 지우기 전에 부른다. 실패해도 예외를 던지지
     * 않는다. 해제를 막지 않기 위해서다.
     *
     * @param manifest 카탈로그의 선언. 빠진 플러그인이면 비어 있고 취소를 건너뛴다
     * @return 취소의 결과. 단추 연결이 아니면 null
     */
    ConnectionOAuthRevoke revoke(ConnectorConnection connection, Optional<ConnectorManifest> manifest);


    /** 값을 붙여 넣어 다시 등록했거나 해제했다. 메모리의 토큰을 지우고 끊김 알림을 읽음으로 바꾼다. 연결의 트랜잭션 안에서 부른다. */
    void forget(ConnectorConnection connection);
}
