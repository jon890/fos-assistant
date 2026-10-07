package com.bifos.assistant.browser.domain;

import tools.jackson.databind.JsonNode;

/**
 * CDP 가 먼저 보낸 사건 하나다.
 *
 * @param method 사건 이름. 예: {@code Page.screencastFrame}
 * @param params 사건 인자. 없으면 빈 객체다
 */
public record CdpEvent(String method, JsonNode params) {}
