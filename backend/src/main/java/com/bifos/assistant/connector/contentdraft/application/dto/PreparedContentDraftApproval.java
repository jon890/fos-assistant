package com.bifos.assistant.connector.contentdraft.application.dto;

import com.bifos.assistant.chat.application.model.ContentDraftBindingStamp;
import com.bifos.assistant.hermes.application.dto.ConnectorManifest;
import java.util.List;
import java.util.UUID;

/**
 * 원고 저장 승인을 잠금 밖에서 준비한 값이다. chat port에는 노출하지 않는다.
 *
 * @param executionIds 잠글 origin과 루트 실행 번호. 중복 없이 오름차순이다
 * @param stamp 지금 catalog로 계산한 바인딩 stamp. 준비하지 못했으면 null
 * @param manifest 준비할 때 읽은 불변 catalog 선언. 읽지 못했으면 null
 * @param startSupported catalog가 시작과 조회를 선언했고 전송 구현이 있는가
 */
public record PreparedContentDraftApproval(UUID actionId, Long jobRowId, Long snapshotRowId, UUID snapshotId,
    Long conversationId, Long agentId, List<Long> executionIds, ContentDraftBindingStamp stamp, ConnectorManifest manifest,
    boolean startSupported) {
    public PreparedContentDraftApproval {
        executionIds = List.copyOf(executionIds);
    }
}
