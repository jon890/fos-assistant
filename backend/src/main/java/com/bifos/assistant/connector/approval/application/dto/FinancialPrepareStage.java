package com.bifos.assistant.connector.approval.application.dto;

import java.util.Locale;

/**
 * 금융 준비를 거절한 검사의 고정 이름이다. 운영 로그에만 {@link #code()} 로 남기고 사용자와 모델에게는 밝히지 않는다.
 * 원격 응답, 인자 값, 계좌, 메일은 담지 않는다. 운영에서 다시 시도할 때 어느 검사부터 볼지 정하는 데 쓴다.
 *
 * <p>{@code ConnectorFinancialProducer} 가 붙이고 {@code ConnectorFinancialIntake} 가 로그에 남긴다.
 */
public enum FinancialPrepareStage {
    /** 트랜잭션 안에서 불렀다. */
    TRANSACTION,
    /** 데이터 key 가 꺼져 snapshot 을 암호화하지 못한다. */
    CIPHER,
    /** keyed hash key 를 읽지 못하거나 이전 key 의 열린 금융 줄이 있다. */
    GATE,
    /** 같은 키의 줄이 다른 도구나 다른 인자로 남아 있다. */
    REPLAY,
    /** 실행의 session 으로 요청자를 찾지 못했다. */
    SESSION,
    /** 그 도구의 서버에 맞는 바인딩이 하나가 아니거나 저장 직전에 사라졌다. */
    BINDING,
    /** 허용 사용자, 에이전트 켜짐, 에이전트·연결 주인, profile 가운데 하나가 맞지 않는다. */
    OWNER,
    /** 바인딩이 READY 가 아니거나 꺼졌거나 재시작을 기다린다. */
    BINDING_STATE,
    /** 연결이 READY 가 아니다. */
    CONNECTION_STATE,
    /** 쓰기를 허용하지 않은 살펴보기 트리다. */
    CHECK,
    /** 커넥터 선언을 읽지 못했거나 schema, 서버, 도구, 금융 보호 선언이 맞지 않는다. */
    MANIFEST,
    /** Hermes 의 커넥터 상태가 켜짐, 설정됨, policy hook, 재시작 없음, bind 모드 가운데 하나가 아니다. */
    CONNECTOR_STATE,
    /** 금융 보호 지원 확인이 verified 가 아니거나 protocol 이 다르다. */
    GUARD_STATE,
    /** 지원 확인의 bindingId, connectionId, connectionUpdatedAt 이 DB 와 다르다. */
    GUARD_REVISION,
    /** 지원 확인의 manifestSha256 이 배포된 선언의 해시와 다르다. */
    GUARD_MANIFEST,
    /** 연결의 계좌가 허용 목록에 없거나 그 항목의 메일이 요청자가 아니다. */
    ACCOUNTS,
    /** 대시보드 준비 HTTP 가 실패했거나 ok 가 아니다. 대시보드의 거절 단계는 대시보드 로그에 있다. */
    PREPARE_HTTP,
    /** 준비 결과가 {@code {v: 1, executionArgs, summary}} 가 아니다. */
    PREPARE_SHAPE,
    /** 준비하는 동안 금융 보호 선언의 해시가 바뀌었다. */
    MANIFEST_CHANGED,
    /** 원래 인자, 실행 인자, 표시 값, scope 의 대조가 맞지 않는다. */
    SNAPSHOT,
    /** 요청자 행을 잠그지 못했다. */
    USER,
    /** 준비하는 동안 바인딩, 연결, 요청자의 맥락이 바뀌었다. */
    CONTEXT_CHANGED,
    /** 같은 주문 의도의 승인 줄이 이미 열려 있다. */
    CONFLICT,
    /** 상한 판정 전에 실행 인자의 금액 모양이 맞지 않는다. 상한에 걸린 것은 이 단계가 아니다. */
    LIMITS,
    /** 저장한 snapshot 에 key 번호가 없다. */
    CONTENT_KEY;


    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
