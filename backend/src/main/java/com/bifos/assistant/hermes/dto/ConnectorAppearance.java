package com.bifos.assistant.hermes.dto;

/**
 * 커넥터 카드의 아이콘과 링크다. 검증을 통과한 값만 담고, 없거나 틀린 칸은 null 이다(ADR-20261008 connector-card).
 *
 * @param icon {@code data:<media type>;base64,<data>} 형태의 data URL
 * @param link {@code https://} 로 시작하는 커넥터 소개나 서비스 주소
 */
public record ConnectorAppearance(String icon, String link) {
    public static final ConnectorAppearance NONE = new ConnectorAppearance(null, null);
}
