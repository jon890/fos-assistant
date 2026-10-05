package com.bifos.assistant.proactive.application;

import java.util.List;
import java.util.UUID;

/** 살펴보기 실행 트리에 아직 남은 커넥터 승인 공개 식별자를 읽는다. */
public interface CheckReportApprovalSource {

    List<UUID> pendingPublicIds(Long rootExecutionId);
}
