package com.bifos.assistant.memory.maintenance.application.dto;

/** 사람이 정비 후보를 확인한 결과다. */
public enum MaintenanceReview {
    /** 후보가 맞다. 이 단계에서는 동작을 적용하지 않고 결과만 남긴다. */
    CORRECT,
    /** 후보가 아니다. */
    INCORRECT
}
