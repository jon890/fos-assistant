package com.bifos.assistant.connector.connection.domain;

/** 연결 방식과 다시 연결 필요 상태다. 제공자 이름이 비면 수동으로 등록한 연결이다. */
public record ConnectionAuth(String provider, boolean reconnectRequired) {
    public static final ConnectionAuth MANUAL = new ConnectionAuth(null, false);


    public ConnectionAuth {
        if (provider == null ? reconnectRequired : provider.isBlank()) {
            throw new IllegalArgumentException("invalid connector authentication");
        }
    }


    public boolean usesOAuth() {
        return provider != null;
    }
}
