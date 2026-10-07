package com.bifos.assistant.browser.domain;

import java.net.URI;
import java.util.List;

/**
 * 브라우저의 탭을 CDP HTTP 창구로 보고 만들고 앞으로 가져온다.
 *
 * <p>{@code cdp} 는 {@link BrowserRuntime#cdpAddress(String)} 가 준 주소다. 대상 번호가 영문자와 숫자가 아니면
 * {@link IllegalArgumentException} 이고, 닿지 못하거나 200 이 아니면 런타임 예외다.
 */
public interface CdpTargets {

    /** 열린 탭이다. 탭이 아닌 대상(service worker 등)은 뺀다. */
    List<CdpTarget> list(URI cdp);

    /** 그 주소로 새 탭을 연다. 주소의 scheme 이 허용한 것인지는 호출자가 확인한다. 여기서는 보지 않는다. */
    CdpTarget create(URI cdp, String url);

    /** 그 탭을 앞으로 가져온다. */
    void activate(URI cdp, String id);
}
