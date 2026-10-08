package com.bifos.assistant.browser.application.model;

import java.net.URI;

/**
 * 켜져 있는 브라우저와 그 CDP 주소다.
 *
 * @param browserId 사용자 브라우저 번호
 * @param cdp 컨테이너 IP 로 된 CDP 주소
 */
public record BrowserEndpoint(Long browserId, URI cdp) {}
