package com.bifos.assistant.hermes.application.dto;

import tools.jackson.databind.JsonNode;

/**
 * 카탈로그가 낸 사건 감지 선언이다(ADR-20261105 / event-watch). 선언하지 않은 커넥터는 null 이다.
 *
 * <p>칸의 계약은 {@code hermes/plugins/dashboard-profile-api/README.md} 의 「event_watch」 가 갖는다.
 *
 * @param tool 대시보드가 실행 밖에서 부르는 조회 도구의 원래 이름
 * @param noun 화면과 알림이 쓰는 항목 이름. 예: 「메일」
 * @param link 원문 링크 틀. {@code {id}} 를 항목 식별자로 바꾼다. {@code https} 주소만 받는다
 */
public record ConnectorEventWatch(String tool, String noun, String link) {


    /** 항목 식별자 자리다. */
    public static final String ID_SLOT = "{id}";

    /** 그 항목의 원문 링크다. */
    public String linkOf(String id) {
        return link.replace(ID_SLOT, id);
    }


    /** 카탈로그 칸을 읽는다. 없거나 모양이 틀리면 null 이다. 틀린 선언 하나로 카탈로그 전체를 버리지 않는다. */
    public static ConnectorEventWatch read(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String tool = text(node, "tool");
        String noun = text(node, "noun");
        String link = text(node, "link");
        if (tool == null || noun == null || link == null || !link.startsWith("https://") || !link.contains(ID_SLOT)) {
            return null;
        }
        return new ConnectorEventWatch(tool, noun, link);
    }


    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
