package com.bifos.assistant.connector.action.domain.type;

import java.util.Arrays;
import java.util.Optional;

/**
 * 사람에게 「내 브라우저」 화면을 넘긴 단계다. 플러그인 결과와 fos-ctx 의 알림에서는 소문자 {@link #wire} 로 온다.
 *
 * <p>어휘는 {@code docs/adr/ADR-20261111-connector-handoff.md} 의 「결정」 이 갖는다. 웹의 {@code handoff.ts} 가 같은 목록과 제목을
 * 쓰고, fos-ctx 의 {@code handoff.py} 는 플러그인이 낼 수 있는 단계만 쓴다. 결제와 탭 확인은 금융 승인의 결과 판정이 직접 적는다
 * ({@code docs/adr/ADR-20261113-purchase-checkout.md}). 플러그인 알림으로는 받지 않는다.
 */
public enum HandoffNeed {
    LOGIN("login", "「내 브라우저」에서 로그인해 주세요", true),
    CAPTCHA("captcha", "「내 브라우저」에서 보안 확인을 풀어 주세요", true),
    DEVICE_CHECK("device_check", "「내 브라우저」에서 기기 인증을 해 주세요", true),
    /** 사이트가 사람인지 의심해 막은 화면이다. 보안문자 그림을 푸는 {@link #CAPTCHA} 와 나눈다. */
    BOT_CHECK("bot_check", "「내 브라우저」에서 자동 접속 확인을 풀어 주세요", true),
    /** 플러그인이 기대한 자리나 칸을 찾지 못했거나 기대한 사이트가 아닌 화면이다. */
    UNEXPECTED_PAGE("unexpected_page", "「내 브라우저」에서 화면을 확인해 주세요", true),
    /** 주문서까지 채웠고 최종 금액이 승인한 예상 합계 안이다. 결제는 사람이 한다(ADR-20261113 / purchase-checkout). */
    PAYMENT("payment", "「내 브라우저」에서 결제해 주세요", false),
    /** 주문서를 채운 결과를 모른다. 다시 보내지 않고 사람이 열린 탭을 본다(spec DECISION33). 탭이 없을 수 있다. */
    TAB_CHECK("tab_check", "「내 브라우저」에 열린 탭을 확인해 주세요", false);


    private final String wire;
    private final String title;
    private final boolean fromPlugin;


    HandoffNeed(String wire, String title, boolean fromPlugin) {
        this.wire = wire;
        this.title = title;
        this.fromPlugin = fromPlugin;
    }


    public String wire() {
        return wire;
    }


    /** 알림과 카드의 제목이다. 웹의 {@code handoff.ts} 와 같은 글이다. */
    public String title() {
        return title;
    }


    /** 플러그인 결과나 fos-ctx 알림이 낸 낱말이다. 모르는 낱말이거나 결과 판정만 적는 단계면 비어 있다. */
    public static Optional<HandoffNeed> fromWire(String value) {
        return Arrays.stream(values()).filter(need -> need.fromPlugin && need.wire.equals(value)).findFirst();
    }
}
