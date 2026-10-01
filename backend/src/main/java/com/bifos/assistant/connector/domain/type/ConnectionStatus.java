package com.bifos.assistant.connector.domain.type;

/** 커넥터 연결의 상태다. DB 에 이름 그대로 저장되므로 값을 바꾸면 마이그레이션이 필요하다. */
public enum ConnectionStatus {
    PENDING,
    READY,
    DISCONNECTED
}
