package com.bifos.assistant.runtime.domain;

/**
 * 실행 하나가 도는 곳이다. 어느 런타임의 어느 격리 단위인지를 값 하나로 가리킨다.
 *
 * <p>{@code location} 과 {@code unit} 의 뜻은 그 종류의 구현이 정한다. 부르는 쪽은 받은 값을 그대로 넘긴다. {@code unit}
 * 은 저장하고, 같은지 비교하고, 그 값으로 조회할 수 있다. 뜻을 풀어 런타임을 직접 부르는 데 쓰지 않는다.
 *
 * @param kind 런타임의 종류. 구현을 고르는 데 쓴다
 * @param location 그 런타임에 닿는 곳. 닿는 곳이 따로 없는 구현이면 null 이다
 * @param unit 사용자의 실행을 서로 나누는 격리 단위
 */
public record RuntimeTarget(RuntimeKind kind, String location, String unit) {

    public RuntimeTarget {
        if (kind == null) {
            throw new IllegalArgumentException("runtime kind is required");
        }
        if (unit == null) {
            throw new IllegalArgumentException("runtime unit is required");
        }
    }
}
