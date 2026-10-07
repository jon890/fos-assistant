package com.bifos.assistant.hermes.dto;

import tools.jackson.databind.JsonNode;

/**
 * 커넥터 도구를 한 번 부른 결과다. 성공 결과와 공통 어휘 가운데 하나만 있다.
 *
 * @param result 도구가 돌려준 JSON. 실패하면 null
 * @param error 실패한 까닭. 성공하면 null
 * @param detail 승인한 실행이 실패했을 때 커넥터가 선언한 오류 코드와 복구 계약(ADR-092). 없으면 null
 */
public record CallResult(JsonNode result, ConnectorCallError error, ConnectorErrorDetail detail) {

    public static CallResult success(JsonNode result) {
        return new CallResult(result, null, null);
    }

    public static CallResult failure(ConnectorCallError error) {
        return new CallResult(null, error, null);
    }

    public static CallResult failure(ConnectorCallError error, ConnectorErrorDetail detail) {
        return new CallResult(null, error, detail);
    }

    public boolean ok() {
        return error == null;
    }
}
