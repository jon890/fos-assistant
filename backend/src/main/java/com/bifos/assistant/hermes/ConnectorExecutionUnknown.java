package com.bifos.assistant.hermes;

import com.bifos.assistant.hermes.dto.ConnectorUnknownDetail;
import java.util.Optional;

/**
 * 승인한 호출의 실행을 보냈으나 실행됐는지 알 수 없다.
 *
 * <p>응답이 시간 안에 오지 않았거나 연결이 끊겼거나 읽을 수 없는 답이 왔을 때다. 받은 쪽은 다시 실행하지 않는다.
 * 원격 응답에는 실행 결과가 섞일 수 있어 메시지와 cause 를 담지 않는다. 커넥터가 선언한 코드와 재시도 식별자만
 * 다시 검증한 값으로 담는다(ADR-20261013 unknown-retry-key).
 */
public class ConnectorExecutionUnknown extends RuntimeException {

    private final transient ConnectorUnknownDetail detail;


    public ConnectorExecutionUnknown() {
        this(null);
    }


    public ConnectorExecutionUnknown(ConnectorUnknownDetail detail) {
        this.detail = detail;
    }


    /** 커넥터가 선언한 코드와 재시도 식별자다. 시간 초과처럼 커넥터가 답하지 않았으면 빈 값이다. */
    public Optional<ConnectorUnknownDetail> detail() {
        return Optional.ofNullable(detail);
    }
}
