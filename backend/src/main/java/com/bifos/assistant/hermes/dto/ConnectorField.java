package com.bifos.assistant.hermes.dto;

/**
 * 커넥터 manifest 의 입력 칸 하나다.
 *
 * <p>{@code env} 는 그 칸 값을 쓸 profile 환경 변수 이름이다. Control Plane 의 응답에는 담지 않는다.
 *
 * @param pattern 값의 형식. 없으면 null
 * @param options 선택지 칸이면 선택지를 읽는 방법. 아니면 null
 */
public record ConnectorField(
        String key,
        String env,
        String label,
        String description,
        boolean secret,
        boolean required,
        String pattern,
        ConnectorFieldOptions options) {}
