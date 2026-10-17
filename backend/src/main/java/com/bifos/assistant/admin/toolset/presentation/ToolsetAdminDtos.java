package com.bifos.assistant.admin.toolset.presentation;

import com.bifos.assistant.agent.application.dto.AgentToolView;
import com.bifos.assistant.agent.application.dto.ToolsetCatalogView;
import com.bifos.assistant.agent.application.dto.ToolsetRequestView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 관리자 toolset 경로가 주고받는 모양이다. 사용자 경로와 계약이 따로 바뀔 수 있어 겹치는 record 는 {@code AgentDtos} 에서 복사해 둔다. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ToolsetAdminDtos {

    public record CatalogToolsetView(
        String name,
        String label,
        String description,
        boolean hidden,
        List<EnabledToolsetAgentView> enabledAgents) {
        static CatalogToolsetView from(ToolsetCatalogView source) {
            return new CatalogToolsetView(
                source.name(),
                source.label(),
                source.description(),
                source.hidden(),
                source.enabledAgents().stream()
                    .map(agent -> new EnabledToolsetAgentView(agent.code(), agent.name()))
                    .toList());
        }
    }

    public record EnabledToolsetAgentView(String code, String name) {
    }

    public record HiddenToolsetsRequest(@NotNull List<@NotBlank String> hidden) {
    }

    public record DecideToolsetRequest(
        @NotNull Boolean approve, @Size(max = 200) String reason) {
    }

    public record ToolsetsView(
        List<ToolsetView> toolsets,
        List<String> unclassifiedEnabled,
        boolean shellOrFileEnabled,
        boolean skillsEnabled) {
    }

    /** 도구 선택 화면이 보여 주는 toolset 한 줄이다. */
    public record ToolsetView(
        String name,
        String label,
        String description,
        String tier,
        boolean enabled,
        boolean editable,
        boolean requiresPrivate,
        boolean hidden) {
        static ToolsetView from(AgentToolView source) {
            return new ToolsetView(
                source.name(),
                source.label(),
                source.description(),
                source.tier().name(),
                source.enabled(),
                source.editable(),
                source.requiresPrivate(),
                source.hidden());
        }
    }

    /** 에이전트 API 실행에 켤 toolset 전체다. */
    public record UpdateToolsetsRequest(@NotNull List<@NotBlank String> enabled) {
    }

    public record ToolsetRequestResponse(
        UUID id,
        String agentCode,
        String agentName,
        boolean agentDeleted,
        String requesterName,
        String toolset,
        String status,
        String reason,
        Instant requestedAt,
        Instant decidedAt) {
        static ToolsetRequestResponse from(ToolsetRequestView row) {
            return new ToolsetRequestResponse(
                row.id(),
                row.agentCode(),
                row.agentName(),
                row.agentDeleted(),
                row.requesterName(),
                row.toolset(),
                row.status().name(),
                row.reason(),
                row.requestedAt(),
                row.decidedAt());
        }
    }
}
