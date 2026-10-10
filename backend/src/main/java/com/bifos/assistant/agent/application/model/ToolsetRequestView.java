package com.bifos.assistant.agent.application.model;

import com.bifos.assistant.agent.domain.type.ToolsetRequestStatus;
import java.time.Instant;
import java.util.UUID;

/** 요청자와 관리자가 읽는 도구 사용 요청이다. 내부 사용자 번호와 profile은 내보내지 않는다. */
public record ToolsetRequestView(
        UUID id,
        String agentCode,
        String agentName,
        boolean agentDeleted,
        String requesterName,
        String toolset,
        ToolsetRequestStatus status,
        String reason,
        Instant requestedAt,
        Instant decidedAt) {}
