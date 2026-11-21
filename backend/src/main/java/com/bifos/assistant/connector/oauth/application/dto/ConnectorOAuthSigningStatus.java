package com.bifos.assistant.connector.oauth.application.dto;

import java.time.Instant;

/**
 * 관리자 화면에 보일 단추 연결 서명 비밀의 상태다. 원문은 담지 않는다.
 *
 * @param storeAvailable KEK 가 있어 새로 만들 수 있는가
 * @param set 비밀이 만들어져 있는가. 처음 쓸 때 서버가 만든다
 * @param updatedBy 마지막으로 새로 만든 사용자 번호. 서버가 처음 만든 값이면 null
 * @param passageConfigured 토큰 통로 주소를 만들 설정이 있는가
 * @param connectionCount 새로 만들면 통로 주소를 다시 쓸 단추 연결 수
 */
public record ConnectorOAuthSigningStatus(
    boolean storeAvailable, boolean set, Long updatedBy, Instant updatedAt, boolean passageConfigured, int connectionCount) {
}
