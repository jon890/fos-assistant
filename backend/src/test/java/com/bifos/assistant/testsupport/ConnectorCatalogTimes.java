package com.bifos.assistant.testsupport;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 커넥터 정책 캐시를 쓰는 검사가 카탈로그 보관 시간을 다루는 자리다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ConnectorCatalogTimes {

    /** test profile 의 카탈로그 보관 시간과 실패 기억 시간(1ms)보다 반드시 긴 대기 시간이다. */
    private static final Duration PAST_CATALOG_TTL = Duration.ofMillis(2);

    /**
     * 앞 검사나 앞 단계가 읽은 카탈로그와 읽기 실패가 지나가기를 기다린다. 캐시는 실제 시각으로 재고, test profile 의 보관 시간과 실패
     * 기억 시간은 1ms 다. 2ms 는 그보다 반드시 길어 다음 읽기가 늘 카탈로그를 다시 읽는다. 캐시가 다시 읽을지 미리 알 방법이 없어 시간으로
     * 기다린다.
     */
    public static void expire() {
        try {
            Thread.sleep(PAST_CATALOG_TTL);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("카탈로그 보관 시간을 기다리다 끊겼다", ex);
        }
    }
}
