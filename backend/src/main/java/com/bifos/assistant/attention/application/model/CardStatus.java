package com.bifos.assistant.attention.application.model;

/** 카드의 원래 기록을 읽었는지다. 읽지 못한 카드는 항목 없이 {@code UNAVAILABLE} 로 낸다. */
public enum CardStatus {
    OK,
    UNAVAILABLE
}
