package com.bifos.assistant.hermes;

import org.springframework.web.client.HttpClientErrorException;

/**
 * 대시보드가 바인딩 설치를 409 {@code sandbox_unavailable} 로 거절했다. 그 커넥터가 실행 공간을 요구하는데 profile 이 정책에 없거나,
 * 사용자 첨부 디렉터리를 확인하지 못했다. 아무것도 바뀌지 않았다.
 *
 * <p>{@link ConnectorInstallConflict} 를 상속하지 않는다. 부르는 쪽이 그 409 와 다른 안내를 하므로 따로 잡는다. 응답 본문은 메시지와
 * cause 에 담지 않는다.
 */
public class ConnectorSandboxUnavailable extends RuntimeException {

    /** 대시보드의 409 를 본문 {@code code} 로 나눈다. {@code sandbox_unavailable} 이면 이 예외, 아니면 설치 충돌이다. */
    static RuntimeException orConflict(HttpClientErrorException ex) {
        if (HermesCallFailure.sandboxRejection(ex).isPresent()) {
            return new ConnectorSandboxUnavailable();
        }
        return new ConnectorInstallConflict();
    }
}
