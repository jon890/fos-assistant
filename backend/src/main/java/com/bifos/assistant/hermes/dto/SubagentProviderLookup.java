package com.bifos.assistant.hermes.dto;

/**
 * 대시보드에서 읽은 자식 session 의 provider 조회 결과다.
 *
 * <p>{@code unavailable} 이 참이면 대시보드에 닿지 못했다는 뜻이고, 잠시 뒤 다시 읽을 수 있다. 거짓이고
 * provider 가 비었으면 대시보드가 답했지만 provider 를 주지 않았다는 뜻이다.
 */
public record SubagentProviderLookup(String provider, String model, boolean unavailable) {

    /** 대시보드가 provider 를 답했다. */
    public static SubagentProviderLookup found(String provider, String model) {
        return new SubagentProviderLookup(provider, model, false);
    }

    /** 대시보드가 답했지만 provider 가 없다. 다시 읽어도 달라지지 않는다. */
    public static SubagentProviderLookup absent() {
        return new SubagentProviderLookup(null, null, false);
    }

    /** 대시보드에 닿지 못했다. */
    public static SubagentProviderLookup unreachable() {
        return new SubagentProviderLookup(null, null, true);
    }
}
