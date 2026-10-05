package com.bifos.assistant.attention.application.model;

/** 후보의 원래 기록을 누가 정했는지다. {@code MODEL_INFERRED} 항목은 {@code NOW} 가 되지 못한다. */
public enum AttentionConfidence {
    CONTROL_PLANE,
    USER_CONFIRMED,
    MODEL_INFERRED
}
