package com.bifos.assistant.hermes.application.dto;

/**
 * 커넥터 카드의 아이콘과 링크다. 검증을 통과한 값만 담고, 없거나 틀린 칸은 null 이다(ADR-20261008 connector-card).
 *
 * @param icon {@code data:<media type>;base64,<data>} 형태의 data URL
 * @param link {@code https://} 로 시작하는 커넥터 소개나 서비스 주소
 * @param readmeUrl 커넥터 README의 {@code https://} 주소
 * @param linkKind 첫 링크의 종류. {@code service} 또는 {@code project}
 */
public record ConnectorAppearance(String icon, String link, String readmeUrl, String linkKind) {


    public static final ConnectorAppearance NONE = new ConnectorAppearance(null, null);

    public ConnectorAppearance {
        linkKind = "project".equals(linkKind) ? "project" : "service";
    }


    /** README와 링크 종류가 없는 옛 카탈로그와 호출자를 받는다. */
    public ConnectorAppearance(String icon, String link) {
        this(icon, link, null, "service");
    }
}
