package com.bifos.assistant.crypto.application.dto;

/** 이미 있는 줄을 매분 조금씩 고치는 배치의 방향이다(ADR-20261008 / data-encryption). 저장하지 않는 값이다. */
public enum BackfillDirection {

    /** key 칸이 비어 있는 평문 줄을 암호화한다. 기본이다. */
    SEAL,

    /** key 칸이 있는 암호문 줄을 평문으로 되돌린다. 옛 버전으로 내리기 직전에만 켠다. */
    UNSEAL,

    /** 돌지 않는다. */
    OFF
}
