package com.bifos.assistant.quality.domain.type;

/** 확인을 시작한 계기다. */
public enum QualityRunTrigger {
    /** 배포 파이프라인이 관리자 토큰으로 불렀다. */
    PIPELINE,
    /** 관리자가 품질 화면에서 시작했다. */
    ADMIN
}
