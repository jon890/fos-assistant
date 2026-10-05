package com.bifos.assistant.proactive.application.model;

import com.bifos.assistant.proactive.domain.CheckReport;
import java.time.Instant;

/** 지금 화면에 낼 수 있도록 필요한 열린 보고의 원래 기록이다. */
public record UnreadCheckReport(
        Long checkId, Long agentId, Long conversationId, Instant startedAt, Instant finishedAt, CheckReport report) {}
