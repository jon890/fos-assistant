package com.bifos.assistant.browser.domain;

import java.net.URI;

/**
 * 브라우저 중계가 CDP 의 HTTP 창구를 부른다.
 *
 * <p>{@code cdp} 는 {@link BrowserRuntime#cdpAddress(String)} 가 준 주소다. 경로는 호출자가 검사한 것만 넘긴다. Chrome 의 상태 코드는
 * 그대로 돌려주고, 닿지 못하거나 본문이 1MB 를 넘으면 런타임 예외다. 예외 메시지에 주소와 본문을 싣지 않는다.
 */
public interface CdpGatewayHttp {

    /** {@code path} 를 GET 으로 부른다. {@code /} 로 시작한다. */
    CdpReply get(URI cdp, String path);

    /** {@code pathAndQuery} 를 본문 없는 PUT 으로 부른다. 쿼리는 받은 원문 그대로다. */
    CdpReply put(URI cdp, String pathAndQuery);
}
