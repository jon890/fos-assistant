package com.bifos.assistant.hermes.dto;

import java.util.List;

/**
 * 대시보드 카탈로그가 내는 커넥터 하나의 선언이다(ADR-043).
 *
 * <p>계약은 {@code docs/connectors.md} 의 「대시보드 plugin 계약」 이 갖는다.
 *
 * @param verifyTool 등록 전에 후보 값으로 부르는 확인 도구
 * @param mcpServer 설치가 그 profile 에 더하는 MCP 서버 이름
 * @param toolsets 연결용 에이전트에 켤 내장 toolset 이름. 선언하지 않았으면 빈 목록
 * @param attachments 참이면 연결용 에이전트의 대화가 사진을 받는다
 * @param schema manifest 의 판. 2 부터 도구마다 정책을 선언한다
 * @param tools 도구마다의 정책 선언. 카탈로그가 내지 않았으면 빈 목록
 */
public record ConnectorManifest(
        String id,
        String title,
        String description,
        List<ConnectorField> fields,
        String verifyTool,
        String mcpServer,
        List<String> toolsets,
        boolean attachments,
        int schema,
        List<ConnectorTool> tools) {

    public ConnectorManifest {
        fields = List.copyOf(fields);
        toolsets = List.copyOf(toolsets);
        tools = List.copyOf(tools);
    }
}
