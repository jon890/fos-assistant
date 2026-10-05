package com.bifos.assistant.proactive.domain;

import java.util.List;

/** 먼저 살펴보기가 남긴 다섯 칸 보고다. 모델이 아닌 Control Plane 이 근거와 승인 항목을 채운다. */
public record CheckReport(
        List<String> changed,
        List<String> done,
        List<String> evidence,
        List<String> needsApproval,
        List<String> next) {}
