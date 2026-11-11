package com.bifos.assistant.connector.action.domain.type;

import java.util.Arrays;
import java.util.Optional;

/**
 * 플러그인이 사람에게 화면을 넘긴 단계다. 플러그인 결과와 fos-ctx 의 알림에서는 소문자 {@link #wire} 로 온다.
 *
 * <p>어휘는 {@code docs/features/naver-shopping/design.md} 의 「넘기기 어휘」 가 갖는다. 웹의 {@code handoff.ts} 와 fos-ctx 의
 * {@code handoff.py} 가 같은 목록을 쓴다. 결제와 탭 확인은 구매 단계(PR3)가 더한다.
 */
public enum HandoffNeed {
    LOGIN("login"),
    CAPTCHA("captcha"),
    DEVICE_CHECK("device_check");

    private final String wire;


    HandoffNeed(String wire) {
        this.wire = wire;
    }


    public String wire() {
        return wire;
    }


    /** 알림이 보낸 낱말이다. 모르는 낱말이면 비어 있다. */
    public static Optional<HandoffNeed> fromWire(String value) {
        return Arrays.stream(values()).filter(need -> need.wire.equals(value)).findFirst();
    }
}
