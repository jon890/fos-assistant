package com.bifos.assistant.connector.application.model;

/**
 * 화면이 입력 칸을 그리는 데 쓰는 선언이다.
 *
 * <p>manifest 의 칸에서 env 이름과 선택지 도구 이름을 뺀 것이다. 그 둘은 Control Plane 밖으로 내지 않는다.
 */
public record ConnectorFieldSummary(
        String key,
        String label,
        String description,
        boolean secret,
        boolean required,
        String pattern,
        boolean hasOptions,
        boolean autoSelectSingle) {}
