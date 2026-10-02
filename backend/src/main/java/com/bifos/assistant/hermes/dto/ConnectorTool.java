package com.bifos.assistant.hermes.dto;

/**
 * 카탈로그가 내는 도구 하나의 정책 선언이다(ADR-049).
 *
 * <p>위험도와 승인 방식을 받은 글자 그대로 담는다. 뜻을 읽고 하한을 검사하는 것은 받는 쪽이 한다.
 *
 * @param risk 위험도 글. 응답에 없으면 null
 * @param approval 승인 방식 글. 응답에 없으면 null
 * @param title 사람에게 보일 이름. 선언하지 않았으면 null
 * @param grant 그 도구에 상시 허락을 줄 수 있는가(ADR-062). 옛 대시보드 plugin 은 이 칸을 내지 않고, 응답에 없으면 null
 *     이다. boolean 이 아닌 값은 거짓으로 읽는다
 */
public record ConnectorTool(String name, String risk, String approval, String title, Boolean grant) {}
