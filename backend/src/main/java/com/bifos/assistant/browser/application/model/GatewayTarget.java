package com.bifos.assistant.browser.application.model;

import java.net.URI;

/**
 * 중계가 요청을 넘길 브라우저다. 접근 표식을 확인하고 그 주인의 브라우저를 켠 결과다.
 *
 * @param userId 브라우저 주인의 사용자 번호
 * @param browserId 사용자 브라우저 번호
 * @param cdp 컨테이너 IP 로 된 CDP 주소
 */
public record GatewayTarget(Long userId, Long browserId, URI cdp) {}
