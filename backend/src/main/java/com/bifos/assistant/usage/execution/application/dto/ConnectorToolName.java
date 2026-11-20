package com.bifos.assistant.usage.execution.application.dto;

/**
 * 작업 과정에 보이는 플러그인 도구의 이름이다(docs/features/app-shell/design.md 「작업 과정의 이름」).
 *
 * @param service 플러그인이 선언한 서비스 이름(manifest 의 {@code title})
 * @param title 플러그인이 그 도구에 선언한 제목. 선언하지 않았으면 null
 */
public record ConnectorToolName(String service, String title) {
}
