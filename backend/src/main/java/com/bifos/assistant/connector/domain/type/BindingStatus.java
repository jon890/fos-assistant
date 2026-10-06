package com.bifos.assistant.connector.domain.type;

/**
 * 에이전트에 붙인 연결의 상태다. DB 에 이름 그대로 저장되므로 값을 바꾸면 마이그레이션이 필요하다.
 *
 * <p>연결 상태가 「값이 확인됐는가」 라면 이 상태는 「그 에이전트의 profile 에 설치되고 반영됐는가」 다.
 */
public enum BindingStatus {
    PENDING,
    READY
}
