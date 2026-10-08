package com.bifos.assistant.hermes.dto;

import java.util.List;

/**
 * 대시보드 카탈로그가 내는 커넥터 하나의 선언이다(ADR-043).
 *
 * <p>계약은 {@code docs/backend/connector-install.md} 의 「대시보드 plugin 계약」 이 갖는다.
 *
 * @param verifyTool 등록 전에 후보 값으로 부르는 확인 도구
 * @param mcpServer 설치가 그 profile 에 더하는 MCP 서버 이름
 * @param toolsets 연결용 에이전트에 켤 내장 toolset 이름. 선언하지 않았으면 빈 목록
 * @param attachments 참이면 연결용 에이전트의 대화가 사진을 받는다
 * @param schema manifest 의 판. 2 부터 도구마다 정책을 선언한다
 * @param tools 도구마다의 정책 선언. 카탈로그가 내지 않았으면 빈 목록
 * @param skills 바인딩 설치가 그 profile 에 복사할 스킬 이름. 옛 대시보드 plugin 은 내지 않고, 없으면 빈 목록
 * @param appearance 카드의 아이콘과 링크. 옛 대시보드 plugin 은 내지 않고, 없으면 {@link ConnectorAppearance#NONE}
 * @param singleBinding 참이면 사용자의 그 연결은 에이전트 하나에만 붙는다(ADR-20261008 / connector-binding-guards). 옛 대시보드
 *     plugin 은 내지 않고, 없으면 거짓
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
        List<ConnectorTool> tools,
        List<String> skills,
        ConnectorAppearance appearance,
        boolean singleBinding) {

    public ConnectorManifest {
        description = description == null ? "" : description;
        fields = List.copyOf(fields);
        toolsets = List.copyOf(toolsets);
        tools = List.copyOf(tools);
        skills = List.copyOf(skills);
        appearance = appearance == null ? ConnectorAppearance.NONE : appearance;
    }

    /** 연결을 에이전트 하나로 제한하지 않는 선언이다. 새 칸을 모르는 대시보드 plugin 의 카탈로그와 검사가 쓴다. */
    public ConnectorManifest(
            String id,
            String title,
            String description,
            List<ConnectorField> fields,
            String verifyTool,
            String mcpServer,
            List<String> toolsets,
            boolean attachments,
            int schema,
            List<ConnectorTool> tools,
            List<String> skills,
            ConnectorAppearance appearance) {
        this(
                id,
                title,
                description,
                fields,
                verifyTool,
                mcpServer,
                toolsets,
                attachments,
                schema,
                tools,
                skills,
                appearance,
                false);
    }

    /** 아이콘과 링크를 내지 않는 선언이다. 새 칸을 모르는 대시보드 plugin 의 카탈로그와 검사가 쓴다. */
    public ConnectorManifest(
            String id,
            String title,
            String description,
            List<ConnectorField> fields,
            String verifyTool,
            String mcpServer,
            List<String> toolsets,
            boolean attachments,
            int schema,
            List<ConnectorTool> tools,
            List<String> skills) {
        this(
                id,
                title,
                description,
                fields,
                verifyTool,
                mcpServer,
                toolsets,
                attachments,
                schema,
                tools,
                skills,
                ConnectorAppearance.NONE);
    }

    /** 스킬을 내지 않는 선언이다. 옛 대시보드 plugin 의 카탈로그와 검사가 쓴다. */
    public ConnectorManifest(
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
        this(id, title, description, fields, verifyTool, mcpServer, toolsets, attachments, schema, tools, List.of());
    }
}
