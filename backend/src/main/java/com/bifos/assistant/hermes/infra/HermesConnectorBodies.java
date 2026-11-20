package com.bifos.assistant.hermes.infra;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link HttpHermesConnectorClient} 가 대시보드에 보내는 본문을 만드는 상태 없는 도우미다. 그 클래스에서 옮겼다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class HermesConnectorBodies {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String OWNER_BROWSER = "owner_browser";


    /** 중계 주소가 null 이면 키를 싣지 않는다. 사용자 브라우저를 쓰지 않는 커넥터의 요청 본문은 그대로다. */
    static Map<String, Object> withOwnerBrowser(Map<String, Object> request, String ownerBrowser) {
        if (ownerBrowser == null) {
            return request;
        }
        Map<String, Object> body = new LinkedHashMap<>(request);
        body.put(OWNER_BROWSER, ownerBrowser);
        return body;
    }


    /** 승인 줄에 저장한 인자 글을 JSON object 로 읽는다. object 로 읽을 수 없으면 null 이다. */
    static JsonNode arguments(String argsJson) {
        try {
            JsonNode args = MAPPER.readTree(argsJson);
            return args != null && args.isObject() ? args : null;
        } catch (JacksonException ex) {
            return null;
        }
    }
}
