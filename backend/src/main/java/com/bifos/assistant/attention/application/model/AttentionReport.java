package com.bifos.assistant.attention.application.model;

import java.util.List;

/** 지금 화면의 보고 카드가 여는 다섯 칸 보고다. */
public record AttentionReport(
        Long checkId,
        String agentCode,
        List<String> changed,
        List<String> done,
        List<String> evidence,
        List<String> needsApproval,
        List<String> next) {}
