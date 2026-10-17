package com.bifos.assistant.connector.action.domain;

/** 저장 위치와 주인에 묶인 승인 본문을 푼다. 표시용 실패 글은 엔티티가 정한다. */
public interface ConnectorActionBodyOpener {
    String open(ConnectorAction action, String column, Long keyId, String stored);
}
