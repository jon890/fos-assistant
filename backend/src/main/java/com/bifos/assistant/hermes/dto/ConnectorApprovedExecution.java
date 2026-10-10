package com.bifos.assistant.hermes.dto;

/** DB에서 읽은 승인 실행 원문이다. 로그의 자동 문자열 변환에도 권한 원문을 남기지 않는다. */
public record ConnectorApprovedExecution(String ticket, String argsJson, String argsSha256) {
    @Override
    public String toString() {
        return "ConnectorApprovedExecution[redacted]";
    }
}
